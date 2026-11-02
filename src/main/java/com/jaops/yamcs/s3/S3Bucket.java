package com.jaops.yamcs.s3;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;

import org.yamcs.buckets.Bucket;
import org.yamcs.buckets.BucketLocation;
import org.yamcs.buckets.BucketProperties;
import org.yamcs.buckets.ObjectProperties;
import org.yamcs.utils.TimeEncoding;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Yamcs bucket stored in an S3 bucket.
 * <p>
 * The bucket can be restricted to the keys starting with a prefix (a "folder" of the S3 bucket); in that case the
 * Yamcs object names are the S3 keys without the prefix.
 * <p>
 * S3 has no creation time for the objects, the last modification time is reported instead. The object listings do
 * not contain the content type and metadata (S3 does not return them when listing), these are returned only by
 * {@link #findObjectAsync(String)}.
 * <p>
 * The size and object count limits are kept in the object {@value #PLACEHOLDER} of the bucket (the S3 key
 * {@code <prefix>.yamcs-bucket}, {@code maxSize=<bytes>} and {@code maxObjects=<count>} lines), such that they survive
 * restarts and are shared by the Yamcs servers using the bucket. The object is not part of the bucket's objects and its
 * name is reserved. The limits are read again from S3 before each put and when the properties are retrieved, and
 * enforced on put by listing the bucket.
 */
public class S3Bucket extends Bucket {

    private final String name;
    private final S3AsyncClient client;
    private final String s3BucketName;
    private final String prefix;

    // As the Yamcs RocksDB and file system buckets
    public static final long DEFAULT_MAX_SIZE = 100L * 1024 * 1024;
    public static final int DEFAULT_MAX_OBJECTS = 1000;

    /**
     * The object holding the bucket limits, and marking the bucket in a folder while it is empty.
     */
    public static final String PLACEHOLDER = ".yamcs-bucket";
    static final String MAX_SIZE_KEY = "maxSize";
    static final String MAX_OBJECTS_KEY = "maxObjects";

    private volatile long maxSize;
    private volatile int maxObjects;

    private volatile long created = TimeEncoding.INVALID_INSTANT;

    /**
     * @param name
     *            the Yamcs bucket name
     * @param client
     *            the S3 client
     * @param s3BucketName
     *            the name of the bucket in S3
     * @param prefix
     *            if not empty, only the S3 keys starting with this prefix belong to the bucket. A slash is appended if
     *            the prefix does not end with one.
     */
    public S3Bucket(String name, S3AsyncClient client, String s3BucketName, String prefix) {
        this(name, client, s3BucketName, prefix, DEFAULT_MAX_SIZE, DEFAULT_MAX_OBJECTS);
    }

    /**
     * @param maxSize
     *            the size limit, until one is read from {@value #PLACEHOLDER}
     * @param maxObjects
     *            the object count limit, until one is read from {@value #PLACEHOLDER}
     */
    public S3Bucket(String name, S3AsyncClient client, String s3BucketName, String prefix, long maxSize,
            int maxObjects) {
        this.name = name;
        this.maxSize = maxSize;
        this.maxObjects = maxObjects;
        this.client = client;
        this.s3BucketName = s3BucketName;
        if (prefix.isEmpty() || prefix.endsWith("/")) {
            this.prefix = prefix;
        } else {
            this.prefix = prefix + "/";
        }
    }

    @Override
    public BucketLocation getLocation() {
        return S3BucketProvider.LOCATION;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getS3BucketName() {
        return s3BucketName;
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * Completes normally if the S3 bucket exists and can be accessed with the configured credentials.
     */
    public CompletableFuture<Void> checkAccessibleAsync() {
        return client.headBucket(b -> b.bucket(s3BucketName)).thenApply(r -> null);
    }

    /**
     * Retrieves the bucket properties, with the limits read again from {@value #PLACEHOLDER}.
     * <p>
     * S3 does not provide the number of objects and the total size of a bucket; they are computed by listing all the
     * objects which may be slow for large buckets.
     */
    @Override
    public CompletableFuture<BucketProperties> getPropertiesAsync() {
        return usageAsync(null)
                .thenCombine(loadLimitsAsync(), (usage, r) -> usage)
                .thenCombine(getCreatedAsync(), (usage, createdTime) -> new BucketProperties(name, createdTime,
                        maxObjects, maxSize, (int) Math.min(usage.count, Integer.MAX_VALUE), usage.size));
    }

    /**
     * The number of objects and total size, and the size of the object objectName (-1 if not found or null).
     */
    private record Usage(long count, long size, long objectSize) {
    }

    private CompletableFuture<Usage> usageAsync(String objectName) {
        var stats = new long[] { 0, 0, -1 }; // [count, size, objectSize]
        return client.listObjectsV2Paginator(b -> b.bucket(s3BucketName).prefix(prefix))
                .contents()
                .subscribe(s3Object -> {
                    var n = toObjectName(s3Object);
                    if (n != null) {
                        stats[0]++;
                        stats[1] += s3Object.size();
                        if (n.equals(objectName)) {
                            stats[2] = s3Object.size();
                        }
                    }
                })
                .thenApply(r -> new Usage(stats[0], stats[1], stats[2]));
    }

    /**
     * Reads the limits from {@value #PLACEHOLDER}. Keeps the current limits if there is none, or for those it does not
     * have.
     */
    CompletableFuture<Void> loadLimitsAsync() {
        return client.getObject(b -> b.bucket(s3BucketName).key(prefix + PLACEHOLDER),
                AsyncResponseTransformer.toBytes())
                .thenAccept(response -> {
                    var props = new Properties();
                    try {
                        props.load(new StringReader(response.asUtf8String()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e); // Not thrown by a StringReader
                    }
                    var size = parse(props.getProperty(MAX_SIZE_KEY));
                    if (size != null) {
                        maxSize = size;
                    }
                    var objects = parse(props.getProperty(MAX_OBJECTS_KEY));
                    if (objects != null) {
                        maxObjects = (int) Math.min(objects, Integer.MAX_VALUE);
                    }
                })
                .exceptionallyCompose(nullIfNotFound());
    }

    private static Long parse(String value) {
        try {
            return value == null ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Writes the limits to {@value #PLACEHOLDER}.
     */
    CompletableFuture<Void> saveLimitsAsync() {
        var text = MAX_SIZE_KEY + "=" + maxSize + "\n" + MAX_OBJECTS_KEY + "=" + maxObjects + "\n";
        return client.putObject(b -> b.bucket(s3BucketName).key(prefix + PLACEHOLDER).contentType("text/plain"),
                AsyncRequestBody.fromString(text)).thenApply(r -> null);
    }

    public long getMaxSize() {
        return maxSize;
    }

    public int getMaxObjects() {
        return maxObjects;
    }

    /**
     * The creation time of the S3 bucket, if it can be found by listing the buckets (requires the corresponding
     * permission). Otherwise the time 0 (1970) is returned.
     */
    private CompletableFuture<Long> getCreatedAsync() {
        if (created != TimeEncoding.INVALID_INSTANT) {
            return CompletableFuture.completedFuture(created);
        }
        return client.listBuckets().handle((response, err) -> {
            long t = TimeEncoding.fromUnixMillisec(0);
            if (err == null) {
                for (var s3Bucket : response.buckets()) {
                    if (s3Bucket.name().equals(s3BucketName) && s3Bucket.creationDate() != null) {
                        t = TimeEncoding.fromJavaInstant(s3Bucket.creationDate());
                    }
                }
            }
            created = t;
            return t;
        });
    }

    /**
     * Sets the size limit and writes it to {@value #PLACEHOLDER}. Does nothing if the limit is already this one.
     */
    @Override
    public void setMaxSize(long maxSize) throws IOException {
        if (maxSize != this.maxSize) {
            this.maxSize = maxSize;
            saveLimits();
        }
    }

    /**
     * Sets the object count limit and writes it to {@value #PLACEHOLDER}. Does nothing if the limit is already this
     * one.
     */
    @Override
    public void setMaxObjects(int maxObjects) throws IOException {
        if (maxObjects != this.maxObjects) {
            this.maxObjects = maxObjects;
            saveLimits();
        }
    }

    private void saveLimits() throws IOException {
        try {
            saveLimitsAsync().join();
        } catch (RuntimeException e) {
            throw new IOException("Cannot save the limits of bucket '" + name + "': " + unwrap(e).getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<ObjectProperties>> listObjectsAsync(String objectPrefix,
            Predicate<ObjectProperties> p) {
        var keyPrefix = objectPrefix == null ? prefix : prefix + objectPrefix;
        var result = new ArrayList<ObjectProperties>();

        // The S3 objects are listed in the UTF-8 binary order of the keys and the subscriber is called sequentially
        var listing = client.listObjectsV2Paginator(b -> b.bucket(s3BucketName).prefix(keyPrefix))
                .contents()
                .subscribe(s3Object -> {
                    var objectName = toObjectName(s3Object);
                    if (objectName != null) {
                        var props = new ObjectProperties(objectName, null,
                                TimeEncoding.fromJavaInstant(s3Object.lastModified()), s3Object.size(), Map.of());
                        if (p.test(props)) {
                            result.add(props);
                        }
                    }
                });
        return listing.thenApply(res -> result);
    }

    /**
     * Stores the object, if the bucket stays within its limits (read again from {@value #PLACEHOLDER}). The usage is
     * computed by listing the bucket; two puts at the same time (from this or another server) may both pass.
     */
    @Override
    public CompletableFuture<Void> putObjectAsync(String objectName, String contentType, Map<String, String> metadata,
            byte[] objectData) {
        if (PLACEHOLDER.equals(objectName)) {
            return reserved();
        }
        return usageAsync(objectName)
                .thenCombine(loadLimitsAsync(), (usage, r) -> usage)
                .thenCompose(usage -> {
                    var replaced = usage.objectSize >= 0;
                    var newSize = usage.size - (replaced ? usage.objectSize : 0) + objectData.length;
                    var newCount = usage.count + (replaced ? 0 : 1);
                    // The messages of the Yamcs buckets
                    if (newSize > maxSize) {
                        return CompletableFuture.failedFuture(
                                new IOException("Maximum bucket size " + maxSize + " exceeded"));
                    }
                    if (newCount > maxObjects) {
                        return CompletableFuture.failedFuture(
                                new IOException("Maximum number of objects in the bucket " + maxObjects + " exceeded"));
                    }
                    return doPutObjectAsync(objectName, contentType, metadata, objectData);
                });
    }

    private CompletableFuture<Void> doPutObjectAsync(String objectName, String contentType,
            Map<String, String> metadata, byte[] objectData) {
        return client.putObject(b -> {
            b.bucket(s3BucketName).key(toKey(objectName));
            if (contentType != null) {
                b.contentType(contentType);
            }
            if (metadata != null && !metadata.isEmpty()) {
                b.metadata(metadata);
            }
        }, AsyncRequestBody.fromBytes(objectData)).thenApply(r -> null);
    }

    @Override
    public CompletableFuture<byte[]> getObjectAsync(String objectName) {
        return client.getObject(b -> b.bucket(s3BucketName).key(toKey(objectName)),
                AsyncResponseTransformer.toBytes())
                .thenApply(ResponseBytes::asByteArrayUnsafe)
                .exceptionallyCompose(nullIfNotFound());
    }

    @Override
    public CompletableFuture<Void> deleteObjectAsync(String objectName) {
        if (PLACEHOLDER.equals(objectName)) {
            return reserved();
        }
        return client.deleteObject(b -> b.bucket(s3BucketName).key(toKey(objectName))).thenApply(r -> null);
    }

    @Override
    public CompletableFuture<ObjectProperties> findObjectAsync(String objectName) {
        return client.headObject(b -> b.bucket(s3BucketName).key(toKey(objectName)))
                .thenApply(response -> new ObjectProperties(objectName,
                        response.contentType(),
                        TimeEncoding.fromJavaInstant(response.lastModified()),
                        response.contentLength(),
                        response.metadata()))
                .exceptionallyCompose(nullIfNotFound());
    }

    /**
     * Deletes all the keys starting with the prefix, including {@value #PLACEHOLDER}, and the folder. Not to be used
     * on a bucket without prefix (the whole S3 bucket).
     */
    CompletableFuture<Void> deleteAllAsync() {
        if (prefix.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Bucket without prefix"));
        }
        // One DeleteObjects request per listing page (at most 1000 keys, the DeleteObjects limit)
        var deletes = new ArrayList<CompletableFuture<?>>();
        return client.listObjectsV2Paginator(b -> b.bucket(s3BucketName).prefix(prefix))
                .subscribe(page -> {
                    if (page.hasContents() && !page.contents().isEmpty()) {
                        var ids = page.contents().stream()
                                .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                                .toList();
                        deletes.add(client.deleteObjects(b -> b.bucket(s3BucketName)
                                .delete(Delete.builder().objects(ids).quiet(true).build()))
                                .thenAccept(response -> {
                                    // Quiet mode: only the keys not deleted are reported
                                    if (response.hasErrors() && !response.errors().isEmpty()) {
                                        var e = response.errors().get(0);
                                        throw new CompletionException(new IOException(response.errors().size()
                                                + " keys not deleted, first " + e.key() + ": " + e.message()));
                                    }
                                }));
                    }
                })
                .thenCompose(r -> CompletableFuture.allOf(deletes.toArray(CompletableFuture[]::new)))
                // The folder itself: S3-compatible stores with directories (SeaweedFS) keep it, and it would be found
                // again as a bucket
                .thenCompose(r -> client.deleteObject(b -> b.bucket(s3BucketName).key(prefix)))
                .thenApply(r -> null);
    }

    private static CompletableFuture<Void> reserved() {
        return CompletableFuture.failedFuture(new IOException("The object name " + PLACEHOLDER + " is reserved"));
    }

    private String toKey(String objectName) {
        return prefix + objectName;
    }

    /**
     * Returns the Yamcs object name corresponding to the S3 object or null if the object does not belong to the
     * bucket ({@value #PLACEHOLDER}, or the "folder" object having the key equal to the prefix that some S3 consoles
     * make).
     */
    private String toObjectName(S3Object s3Object) {
        var objectName = s3Object.key().substring(prefix.length());
        return objectName.isEmpty() || objectName.equals(PLACEHOLDER) ? null : objectName;
    }

    private static <T> Function<Throwable, CompletableFuture<T>> nullIfNotFound() {
        return err -> {
            var cause = unwrap(err);
            if (cause instanceof S3Exception s3e && s3e.statusCode() == 404) {
                return CompletableFuture.completedFuture(null);
            } else {
                return CompletableFuture.failedFuture(cause);
            }
        };
    }

    static Throwable unwrap(Throwable err) {
        while ((err instanceof CompletionException || err instanceof ExecutionException) && err.getCause() != null) {
            err = err.getCause();
        }
        return err;
    }
}
