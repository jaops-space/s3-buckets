package com.jaops.yamcs.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.buckets.ObjectProperties;
import org.yamcs.utils.TimeEncoding;

import software.amazon.awssdk.core.async.AsyncRequestBody;
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

        var config = new HashMap<String, Object>();
        config.put("endpoint", "http://" + s3Server.getHost() + ":" + s3Server.getMappedPort(8333));
        config.put("region", "us-east-1");
        config.put("accessKeyId", "test");
        config.put("secretAccessKey", "test");
        config.put("pathStyleAccess", true);
        config.put("buckets", List.of(bucketConfig));

        return YConfiguration.wrap(new S3BucketProvider().getSpec().validate(config));
    }

    S3Bucket bucket(String prefix) {
        return new S3Bucket("yamcs-bucket", client, s3BucketName, base + prefix);
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
    }

    static List<String> names(List<ObjectProperties> objects) {
        return objects.stream().map(ObjectProperties::name).toList();
    }
}
