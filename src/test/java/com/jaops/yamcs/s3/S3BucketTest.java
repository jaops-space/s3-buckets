package com.jaops.yamcs.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.buckets.Bucket;
import org.yamcs.buckets.ObjectProperties;
import org.yamcs.utils.TimeEncoding;

import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;

@Testcontainers(disabledWithoutDocker = true)
public class S3BucketTest {

    // SeaweedFS S3 gateway (without authentication configured, it accepts any credentials)
    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> s3Server = new GenericContainer<>("chrislusf/seaweedfs:4.46")
            .withCommand("server", "-s3", "-dir=/data", "-ip.bind=0.0.0.0")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/").forPort(8333));

    static S3AsyncClient client;
    static String s3BucketName = "yamcs-test";

    // All tests share the same S3 bucket, each one uses a different key prefix
    String base = "t-" + UUID.randomUUID() + "/";

    @BeforeAll
    static void setUpClient() throws ValidationException {
        TimeEncoding.setUp();
        client = S3BucketProvider.createClient(providerConfig(s3BucketName, Map.of()));
        client.createBucket(b -> b.bucket(s3BucketName)).join();
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    static YConfiguration providerConfig(String s3BucketName, Map<String, Object> bucketOptions)
            throws ValidationException {
        var bucketConfig = new HashMap<String, Object>(bucketOptions);
        bucketConfig.put("name", s3BucketName);
        return providerConfig(Map.of("buckets", List.of(bucketConfig)));
    }

    static YConfiguration providerConfig(Map<String, Object> options) throws ValidationException {
        var config = new HashMap<String, Object>(options);
        config.put("endpoint", "http://" + s3Server.getHost() + ":" + s3Server.getMappedPort(8333));
        config.put("region", "us-east-1");
        config.put("accessKeyId", "test");
        config.put("secretAccessKey", "test");
        config.put("pathStyleAccess", true);

        return YConfiguration.wrap(new S3BucketProvider().getSpec().validate(config));
    }

    // Without limits, for the tests not about them
    S3Bucket bucket(String prefix) {
        return new S3Bucket("yamcs-bucket", client, s3BucketName, base + prefix, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetFind() {
        var bucket = bucket("");
        var data = bytes("hello s3");
        bucket.putObjectAsync("dir/obj1.txt", "text/plain", Map.of("key1", "value1"), data).join();

        assertArrayEquals(data, bucket.getObjectAsync("dir/obj1.txt").join());

        var props = bucket.findObjectAsync("dir/obj1.txt").join();
        assertEquals("dir/obj1.txt", props.name());
        assertEquals("text/plain", props.contentType());
        assertEquals(data.length, props.size());
        assertEquals(Map.of("key1", "value1"), props.metadata());
        assertTrue(Math.abs(props.created() - TimeEncoding.getWallclockTime()) < 60_000);
    }

    @Test
    void putWithoutContentTypeAndMetadata() {
        var bucket = bucket("");
        bucket.putObjectAsync("obj", null, null, new byte[0]).join();

        var props = bucket.findObjectAsync("obj").join();
        assertEquals(0, props.size());
        assertTrue(props.metadata().isEmpty());
        assertArrayEquals(new byte[0], bucket.getObjectAsync("obj").join());
    }

    @Test
    void missingObject() {
        var bucket = bucket("");
        assertNull(bucket.getObjectAsync("missing").join());
        assertNull(bucket.findObjectAsync("missing").join());
    }

    @Test
    void overwriteAndDelete() {
        var bucket = bucket("");
        bucket.putObjectAsync("obj", null, null, bytes("v1")).join();
        bucket.putObjectAsync("obj", null, null, bytes("version2")).join();
        assertArrayEquals(bytes("version2"), bucket.getObjectAsync("obj").join());

        bucket.deleteObjectAsync("obj").join();
        assertNull(bucket.findObjectAsync("obj").join());
        assertTrue(bucket.listObjectsAsync().join().isEmpty());
    }

    @Test
    void listObjects() {
        var bucket = bucket("");
        for (var name : List.of("b/2", "a/1", "b/1", "c", "a/2/x")) {
            bucket.putObjectAsync(name, null, null, bytes(name)).join();
        }

        assertEquals(List.of("a/1", "a/2/x", "b/1", "b/2", "c"), names(bucket.listObjectsAsync().join()));
        assertEquals(List.of("b/1", "b/2"), names(bucket.listObjectsAsync("b/").join()));
        assertEquals(List.of("a/1", "a/2/x", "c"),
                names(bucket.listObjectsAsync(p -> !p.name().startsWith("b")).join()));

        var props = bucket.listObjectsAsync("c").join().get(0);
        assertEquals(1, props.size());
        assertNull(props.contentType());
        assertTrue(props.metadata().isEmpty());
    }

    @Test
    void listManyObjects() {
        // More than one page (1000 keys) of the S3 listing
        var bucket = bucket("");
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (int i = 0; i < 1100; i++) {
            futures.add(bucket.putObjectAsync(String.format("obj%04d", i), null, null, new byte[1]));
        }
        futures.forEach(f -> f.join());
        assertEquals(1100, bucket.listObjectsAsync().join().size());
        assertEquals(1100, bucket.getPropertiesAsync().join().numObjects());
    }

    @Test
    void prefixIsolation() {
        var bucketA = bucket("a");
        var bucketB = bucket("b/");
        assertEquals(base + "a/", bucketA.getPrefix());

        bucketA.putObjectAsync("obj", null, null, bytes("in a")).join();
        bucketB.putObjectAsync("obj", null, null, bytes("in b")).join();
        // Not part of bucketA despite starting with "a"
        bucket("").putObjectAsync("ab/obj", null, null, bytes("in ab")).join();

        assertArrayEquals(bytes("in a"), bucketA.getObjectAsync("obj").join());
        assertArrayEquals(bytes("in b"), bucketB.getObjectAsync("obj").join());
        assertEquals(List.of("obj"), names(bucketA.listObjectsAsync().join()));
        assertNotNull(bucket("").findObjectAsync("a/obj").join());

        bucketA.deleteObjectAsync("obj").join();
        assertNull(bucketA.findObjectAsync("obj").join());
        assertNotNull(bucketB.findObjectAsync("obj").join());
    }

    @Test
    void folderPlaceholderIgnored() {
        // Some S3 consoles create an empty object with the folder name as key
        client.putObject(b -> b.bucket(s3BucketName).key(base + "folder/"), AsyncRequestBody.empty()).join();
        var bucket = bucket("folder");
        bucket.putObjectAsync("obj", null, null, bytes("x")).join();

        assertEquals(List.of("obj"), names(bucket.listObjectsAsync().join()));
        assertEquals(1, bucket.getPropertiesAsync().join().numObjects());
    }

    @Test
    void properties() {
        var bucket = bucket("");
        bucket.putObjectAsync("obj1", null, null, new byte[10]).join();
        bucket.putObjectAsync("obj2", null, null, new byte[20]).join();

        var props = bucket.getPropertiesAsync().join();
        assertEquals("yamcs-bucket", props.name());
        assertEquals(2, props.numObjects());
        assertEquals(30, props.size());
        assertEquals(Long.MAX_VALUE, props.maxSize());
        assertEquals(Integer.MAX_VALUE, props.maxNumObjects());

        var defaults = new S3Bucket("b", client, s3BucketName, base + "defaults").getPropertiesAsync().join();
        assertEquals(100L * 1024 * 1024, defaults.maxSize());
        assertEquals(1000, defaults.maxNumObjects());
        // The S3 bucket creation time if listing the buckets is allowed, otherwise 1970
        assertTrue(props.created() == TimeEncoding.fromUnixMillisec(0)
                || Math.abs(props.created() - TimeEncoding.getWallclockTime()) < 60_000);
    }

    @Test
    void checkAccessible() {
        bucket("").checkAccessibleAsync().join();
        var missing = new S3Bucket("x", client, "missing-" + UUID.randomUUID(), "");
        assertThrows(Exception.class, () -> missing.checkAccessibleAsync().join());
    }

    @Test
    void provider() throws Exception {
        var provider = new S3BucketProvider();
        var buckets = provider.loadBuckets(providerConfig(s3BucketName, Map.of("localName", "local", "prefix", base + "p")));
        assertEquals(1, buckets.size());

        var bucket = (S3Bucket) buckets.get(0);
        assertEquals("local", bucket.getName());
        assertEquals(S3BucketProvider.LOCATION, bucket.getLocation());
        assertEquals(s3BucketName, bucket.getS3BucketName());
        assertEquals(base + "p/", bucket.getPrefix());

        bucket.putObjectAsync("obj", null, null, bytes("x")).join();
        assertNotNull(bucket("").findObjectAsync("p/obj").join());
    }

    @Test
    void providerSpec() {
        var spec = new S3BucketProvider().getSpec();
        var config = new HashMap<String, Object>();
        config.put("region", "gra");
        config.put("accessKeyId", "key");
        config.put("buckets", List.of(Map.of("name", "b")));
        assertThrows(ValidationException.class, () -> spec.validate(config));

        config.put("secretAccessKey", "secret");
        config.put("checksums", "SOMETIMES");
        assertThrows(ValidationException.class, () -> spec.validate(config));

        // buckets or folder
        config.remove("checksums");
        config.remove("buckets");
        assertThrows(ValidationException.class, () -> spec.validate(config));
        config.put("folder", Map.of("name", "b"));
        assertDoesNotThrow(() -> spec.validate(config));
    }

    YConfiguration folderConfig(String prefix, List<Map<String, Object>> buckets) throws ValidationException {
        var options = new HashMap<String, Object>();
        options.put("folder", Map.of("name", s3BucketName, "prefix", base + prefix));
        if (!buckets.isEmpty()) {
            options.put("buckets", buckets);
        }
        return providerConfig(options);
    }

    static List<String> bucketNames(List<Bucket> buckets) {
        return buckets.stream().map(Bucket::getName).sorted().toList();
    }

    @Test
    void folderCreateLoadDelete() throws Exception {
        var provider = new S3BucketProvider();
        assertTrue(provider.loadBuckets(folderConfig("yamcs", List.of())).isEmpty());

        var displays = (S3Bucket) provider.createBucket("displays");
        assertEquals(base + "yamcs/displays/", displays.getPrefix());
        assertTrue(displays.listObjectsAsync().join().isEmpty());
        displays.putObjectAsync("a/b.opi", null, null, bytes("x")).join();
        provider.createBucket("cfdpDown"); // empty: kept by its placeholder

        // The next start finds both
        var restarted = new S3BucketProvider();
        var buckets = restarted.loadBuckets(folderConfig("yamcs", List.of()));
        assertEquals(List.of("cfdpDown", "displays"), bucketNames(buckets));
        var reloaded = buckets.stream().filter(b -> b.getName().equals("displays")).findFirst().get();
        assertEquals(List.of("a/b.opi"), names(reloaded.listObjectsAsync().join()));

        // Yamcs deletes only empty buckets
        var cfdpDown = buckets.stream().filter(b -> b.getName().equals("cfdpDown")).findFirst().get();
        restarted.deleteBucket(cfdpDown);
        var remaining = new S3BucketProvider().loadBuckets(folderConfig("yamcs", List.of()));
        assertEquals(List.of("displays"), bucketNames(remaining));
    }

    @Test
    void folderCreateExisting() throws Exception {
        // An existing sub-folder without placeholder becomes the bucket, with its objects
        bucket("f/stacks").putObjectAsync("s1", null, null, bytes("x")).join();
        var provider = new S3BucketProvider();
        provider.loadBuckets(folderConfig("f", List.of()));
        var stacks = provider.createBucket("stacks");
        assertEquals(List.of("s1"), names(stacks.listObjectsAsync().join()));
    }

    @Test
    void folderIgnoredNames() throws Exception {
        bucket("f/displays").putObjectAsync("x", null, null, bytes("folder")).join();
        bucket("f/not valid").putObjectAsync("x", null, null, bytes("x")).join();
        bucket("f/").putObjectAsync("top-level-object", null, null, bytes("x")).join();
        bucket("elsewhere").putObjectAsync("x", null, null, bytes("configured")).join();

        // The configured bucket wins over the folder's of the same name
        var provider = new S3BucketProvider();
        var buckets = provider.loadBuckets(folderConfig("f", List.of(
                Map.of("name", s3BucketName, "localName", "displays", "prefix", base + "elsewhere"))));
        assertEquals(List.of("displays"), bucketNames(buckets));
        assertArrayEquals(bytes("configured"), buckets.get(0).getObjectAsync("x").join());
        assertThrows(UnsupportedOperationException.class, () -> provider.deleteBucket(buckets.get(0)));
    }

    @Test
    void noFolder() throws Exception {
        var provider = new S3BucketProvider();
        var buckets = provider.loadBuckets(providerConfig(s3BucketName, Map.of("prefix", base)));
        assertThrows(UnsupportedOperationException.class, () -> provider.createBucket("displays"));
        assertThrows(UnsupportedOperationException.class, () -> provider.deleteBucket(buckets.get(0)));
    }

    @Test
    void deleteMany() {
        // More keys than one DeleteObjects request
        var bucket = bucket("many");
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (int i = 0; i < 1100; i++) {
            futures.add(bucket.putObjectAsync("obj" + i, null, null, new byte[1]));
        }
        futures.forEach(f -> f.join());
        bucket.deleteAllAsync().join();
        assertTrue(bucket.listObjectsAsync().join().isEmpty());
    }

    @Test
    void limitsEnforced() throws Exception {
        var bucket = new S3Bucket("b", client, s3BucketName, base + "limited", 10, 2);
        bucket.putObjectAsync("a", null, null, new byte[6]).join();
        var e = assertThrows(CompletionException.class,
                () -> bucket.putObjectAsync("b", null, null, new byte[5]).join());
        assertEquals("Maximum bucket size 10 exceeded", e.getCause().getMessage());
        // Replacing an object counts its new size only
        bucket.putObjectAsync("a", null, null, new byte[9]).join();
        bucket.putObjectAsync("b", null, null, new byte[1]).join();
        e = assertThrows(CompletionException.class, () -> bucket.putObjectAsync("c", null, null, new byte[0]).join());
        assertEquals("Maximum number of objects in the bucket 2 exceeded", e.getCause().getMessage());
        assertEquals(List.of("a", "b"), names(bucket.listObjectsAsync().join()));

        // Raised: in .yamcs-bucket, which is not one of the objects
        bucket.setMaxObjects(3);
        bucket.putObjectAsync("c", null, null, new byte[0]).join();
        assertEquals(3, bucket.getPropertiesAsync().join().numObjects());
    }

    @Test
    void limitsStoredAndShared() throws Exception {
        var provider = new S3BucketProvider();
        provider.loadBuckets(folderConfig("f", List.of()));
        var bucket = (S3Bucket) provider.createBucket("cfdpDown");
        bucket.setMaxSize(5000);
        bucket.setMaxObjects(7);

        // Another server (or the next start) reads them
        var other = (S3Bucket) new S3BucketProvider().loadBuckets(folderConfig("f", List.of())).get(0);
        assertEquals(5000, other.getMaxSize());
        assertEquals(7, other.getMaxObjects());

        // Changed on one, seen by the other at its next put or properties
        bucket.setMaxSize(3);
        var e = assertThrows(CompletionException.class,
                () -> other.putObjectAsync("x", null, null, new byte[4]).join());
        assertEquals("Maximum bucket size 3 exceeded", e.getCause().getMessage());
        bucket.setMaxObjects(8);
        assertEquals(8, other.getPropertiesAsync().join().maxNumObjects());

        // .yamcs-bucket holds them, a reserved name
        var text = client.getObject(b -> b.bucket(s3BucketName).key(base + "f/cfdpDown/.yamcs-bucket"),
                AsyncResponseTransformer.toBytes()).join().asUtf8String();
        assertEquals("maxSize=3\nmaxObjects=8\n", text);
        assertTrue(bucket.listObjectsAsync().join().isEmpty());
        assertThrows(CompletionException.class,
                () -> bucket.putObjectAsync(".yamcs-bucket", null, null, new byte[0]).join());
        assertThrows(CompletionException.class, () -> bucket.deleteObjectAsync(".yamcs-bucket").join());

        // Creating an existing bucket again (another server) keeps them; a new one gets the defaults
        var second = new S3BucketProvider();
        second.loadBuckets(folderConfig("f", List.of()));
        var recreated = (S3Bucket) second.createBucket("cfdpDown");
        assertEquals(3, recreated.getMaxSize());
        assertEquals(8, recreated.getMaxObjects());
        assertEquals(1000, ((S3Bucket) second.createBucket("fresh")).getMaxObjects());
    }

    @Test
    void limitsOfMappedBucket() throws Exception {
        // A mapping with a prefix that has no .yamcs-bucket yet gets one when a limit is set
        var mapped = (S3Bucket) new S3BucketProvider()
                .loadBuckets(providerConfig(s3BucketName, Map.of("prefix", base + "m"))).get(0);
        assertEquals(1000, mapped.getMaxObjects());
        mapped.setMaxObjects(20);
        var reloaded = (S3Bucket) new S3BucketProvider()
                .loadBuckets(providerConfig(s3BucketName, Map.of("prefix", base + "m"))).get(0);
        assertEquals(20, reloaded.getMaxObjects());
    }

    @Test
    void providerDefaultLimits() throws Exception {
        // A folder without placeholder gets the provider entry's limits
        bucket("f/displays").putObjectAsync("x", null, null, bytes("x")).join();
        var options = new HashMap<String, Object>();
        options.put("folder", Map.of("name", s3BucketName, "prefix", base + "f"));
        options.put("maxSize", 1L << 40);
        options.put("maxObjects", 50000);
        var displays = (S3Bucket) new S3BucketProvider().loadBuckets(providerConfig(options)).get(0);
        assertEquals(1L << 40, displays.getMaxSize());
        assertEquals(50000, displays.getMaxObjects());
    }

    static List<String> names(List<ObjectProperties> objects) {
        return objects.stream().map(ObjectProperties::name).toList();
    }
}
