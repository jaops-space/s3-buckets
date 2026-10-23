package com.jaops.yamcs.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * The size and object count limits are managed on the S3 side; this bucket does not enforce any limit and reports
 * the maximum values as limits.
 */
public class S3Bucket extends Bucket {

    private final String name;
    private final S3AsyncClient client;
    private final String s3BucketName;
    private final String prefix;

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
        this.name = name;
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
     * Retrieves the bucket properties.
     * <p>
     * S3 does not provide the number of objects and the total size of a bucket; they are computed by listing all the
     * objects which may be slow for large buckets.
     */
    @Override
    public CompletableFuture<BucketProperties> getPropertiesAsync() {
        var stats = new long[2]; // [count, size]
        var listing = client.listObjectsV2Paginator(b -> b.bucket(s3BucketName).prefix(prefix))
                .contents()
                .subscribe(s3Object -> {
                    if (toObjectName(s3Object) != null) {
                        stats[0]++;
                        stats[1] += s3Object.size();
                    }
                });
        return listing.thenCombine(getCreatedAsync(), (res, createdTime) -> {
            // No limits on the S3 side: report the maximum values (0 would mean that the bucket is full and for
            // example the CFDP service would refuse to download files into it)
            return new BucketProperties(name, createdTime, Integer.MAX_VALUE, Long.MAX_VALUE,
                    (int) Math.min(stats[0], Integer.MAX_VALUE), stats[1]);
        });
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

    @Override
    public void setMaxSize(long maxSize) {
        // Ignore, managed by S3
    }

    @Override
    public void setMaxObjects(int maxObjects) {
        // Ignore, managed by S3
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

    @Override
    public CompletableFuture<Void> putObjectAsync(String objectName, String contentType, Map<String, String> metadata,
            byte[] objectData) {
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

    private String toKey(String objectName) {
        return prefix + objectName;
    }

    /**
     * Returns the Yamcs object name corresponding to the S3 object or null if the object does not belong to the
     * bucket (the "folder" placeholder object having the key equal to the prefix).
     */
    private String toObjectName(S3Object s3Object) {
        var objectName = s3Object.key().substring(prefix.length());
        return objectName.isEmpty() ? null : objectName;
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
