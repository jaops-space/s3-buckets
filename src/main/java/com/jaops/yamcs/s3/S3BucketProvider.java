package com.jaops.yamcs.s3;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.buckets.Bucket;
import org.yamcs.buckets.BucketLocation;
import org.yamcs.buckets.BucketProvider;
import org.yamcs.logging.Log;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CommonPrefix;

/**
 * Provides Yamcs buckets backed by S3 (or S3-compatible) buckets.
 * <p>
 * Each provider entry creates one S3 client which is shared by all the buckets of that entry.
 * <p>
 * The buckets are those listed in the configuration, and with a {@code folder}, each sub-folder of the folder (an S3
 * bucket and a key prefix): those found at startup, and those created from Yamcs (the provider entry having
 * {@code createBuckets: true}) with their {@value S3Bucket#PLACEHOLDER} object. Only the buckets of the folder can be
 * deleted from Yamcs.
 * <p>
 * The limits of a bucket are kept in its {@value S3Bucket#PLACEHOLDER} (see {@link S3Bucket}); a bucket without has
 * the {@code maxSize} and {@code maxObjects} of the provider entry.
 */
public class S3BucketProvider implements BucketProvider {

    public static final BucketLocation LOCATION = new BucketLocation("s3", "S3");

    private static final Log log = new Log(S3BucketProvider.class);

    static final String CHECKSUMS_WHEN_SUPPORTED = "WHEN_SUPPORTED";
    static final String CHECKSUMS_WHEN_REQUIRED = "WHEN_REQUIRED";

    // As the Yamcs HTTP API, for the bucket names found in the folder
    static final Pattern BUCKET_NAME = Pattern.compile("\\w[\\w\\-]+");

    private S3AsyncClient client;
    private long defaultMaxSize;
    private int defaultMaxObjects;
    // The S3 bucket and key prefix (empty or ending with a slash) of the folder; null without folder
    private String folderS3BucketName;
    private String folderPrefix;
    // The names of the buckets in the folder, which may be deleted
    private final Set<String> folderBuckets = new HashSet<>();

    @Override
    public BucketLocation getLocation() {
        return LOCATION;
    }

    @Override
    public Spec getSpec() {
        var bucketSpec = new Spec();
        bucketSpec.addOption("name", OptionType.STRING).withRequired(true);
        bucketSpec.addOption("localName", OptionType.STRING);
        bucketSpec.addOption("prefix", OptionType.STRING);

        var folderSpec = new Spec();
        folderSpec.addOption("name", OptionType.STRING).withRequired(true);
        folderSpec.addOption("prefix", OptionType.STRING);

        var spec = new Spec();
        spec.addOption("endpoint", OptionType.STRING);
        spec.addOption("region", OptionType.STRING).withRequired(true);
        spec.addOption("accessKeyId", OptionType.STRING);
        spec.addOption("secretAccessKey", OptionType.STRING).withSecret(true);
        spec.addOption("pathStyleAccess", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("checksums", OptionType.STRING)
                .withChoices(CHECKSUMS_WHEN_SUPPORTED, CHECKSUMS_WHEN_REQUIRED);
        spec.addOption("buckets", OptionType.LIST)
                .withElementType(OptionType.MAP)
                .withSpec(bucketSpec);
        spec.addOption("folder", OptionType.MAP).withSpec(folderSpec);
        spec.addOption("maxSize", OptionType.INTEGER).withDefault(S3Bucket.DEFAULT_MAX_SIZE);
        spec.addOption("maxObjects", OptionType.INTEGER).withDefault(S3Bucket.DEFAULT_MAX_OBJECTS);
        spec.requireOneOf("buckets", "folder");
        spec.requireTogether("accessKeyId", "secretAccessKey");
        return spec;
    }

    @Override
    public List<Bucket> loadBuckets(YConfiguration config) throws IOException {
        client = createClient(config);
        defaultMaxSize = config.getLong("maxSize");
        defaultMaxObjects = config.getInt("maxObjects");

        var buckets = new ArrayList<Bucket>();
        var names = new HashSet<String>();
        if (config.containsKey("buckets")) {
            for (var bucketConfig : config.getConfigList("buckets")) {
                var s3BucketName = bucketConfig.getString("name");
                var localName = bucketConfig.getString("localName", s3BucketName);
                var prefix = bucketConfig.getString("prefix", "");

                var bucket = new S3Bucket(localName, client, s3BucketName, prefix, defaultMaxSize, defaultMaxObjects);
                checkAccessible(bucket);
                buckets.add(bucket);
                names.add(localName);
            }
        }

        if (config.containsKey("folder")) {
            var folderConfig = config.getConfig("folder");
            folderS3BucketName = folderConfig.getString("name");
            folderPrefix = folderConfig.getString("prefix", "");
            if (!folderPrefix.isEmpty() && !folderPrefix.endsWith("/")) {
                folderPrefix += "/";
            }
            for (var name : listFolder()) {
                if (names.contains(name)) {
                    log.warn("Ignoring the folder s3://{}/{}{}/: a bucket named '{}' is configured",
                            folderS3BucketName, folderPrefix, name, name);
                } else if (!BUCKET_NAME.matcher(name).matches()) {
                    log.warn("Ignoring the folder s3://{}/{}{}/: not a valid bucket name",
                            folderS3BucketName, folderPrefix, name);
                } else {
                    buckets.add(folderBucket(name));
                }
            }
        }

        // The stored limits; they are read again before each put, so a bucket that fails here gets them later
        for (var bucket : buckets) {
            var s3Bucket = (S3Bucket) bucket;
            try {
                s3Bucket.loadLimitsAsync().join();
            } catch (RuntimeException e) {
                log.warn("Cannot read the limits of bucket '{}': {}", bucket.getName(),
                        S3Bucket.unwrap(e).getMessage());
            }
        }
        return buckets;
    }

    /**
     * The names of the sub-folders of the folder.
     */
    private List<String> listFolder() throws IOException {
        var names = new ArrayList<String>();
        try {
            client.listObjectsV2Paginator(b -> b.bucket(folderS3BucketName).prefix(folderPrefix).delimiter("/"))
                    .commonPrefixes()
                    .subscribe((CommonPrefix p) -> {
                        var name = p.prefix().substring(folderPrefix.length());
                        names.add(name.substring(0, name.length() - 1));
                    }).join();
        } catch (RuntimeException e) {
            throw new IOException("Cannot list the folder s3://" + folderS3BucketName + "/" + folderPrefix + ": "
                    + S3Bucket.unwrap(e).getMessage(), e);
        }
        return names;
    }

    private S3Bucket folderBucket(String name) {
        folderBuckets.add(name);
        return new S3Bucket(name, client, folderS3BucketName, folderPrefix + name + "/", defaultMaxSize,
                defaultMaxObjects);
    }

    /**
     * Creates the bucket as a sub-folder of the folder, with its {@value S3Bucket#PLACEHOLDER} holding the default
     * limits, such that it is found at the next start while empty. An existing sub-folder of that name becomes the
     * bucket, with its objects and its stored limits.
     */
    @Override
    public Bucket createBucket(String bucketName) throws IOException {
        if (folderS3BucketName == null) {
            throw new UnsupportedOperationException(
                    "The S3 bucket provider creates buckets only in a folder (option 'folder')");
        }
        var bucket = folderBucket(bucketName);
        try {
            bucket.loadLimitsAsync().thenCompose(r -> bucket.saveLimitsAsync()).join();
        } catch (RuntimeException e) {
            folderBuckets.remove(bucketName);
            throw new IOException("Cannot create the bucket '" + bucketName + "' in s3://" + folderS3BucketName + "/"
                    + bucket.getPrefix() + ": " + S3Bucket.unwrap(e).getMessage(), e);
        }
        log.info("Created bucket '{}' in s3://{}/{}", bucketName, folderS3BucketName, bucket.getPrefix());
        return bucket;
    }

    /**
     * Deletes a bucket of the folder: all the keys of its sub-folder (Yamcs deletes only empty buckets, so normally
     * only its {@value S3Bucket#PLACEHOLDER}). The buckets listed in the configuration cannot be deleted.
     */
    @Override
    public void deleteBucket(Bucket bucket) throws IOException {
        if (!(bucket instanceof S3Bucket s3Bucket) || !folderBuckets.contains(bucket.getName())) {
            throw new UnsupportedOperationException(
                    "Bucket '" + bucket.getName() + "' is configured in yamcs.yaml and cannot be deleted");
        }
        try {
            s3Bucket.deleteAllAsync().join();
        } catch (RuntimeException e) {
            throw new IOException("Cannot delete the bucket '" + bucket.getName() + "': "
                    + S3Bucket.unwrap(e).getMessage(), e);
        }
        folderBuckets.remove(bucket.getName());
        log.info("Deleted bucket '{}' (s3://{}/{})", bucket.getName(), folderS3BucketName, s3Bucket.getPrefix());
    }

    static S3AsyncClient createClient(YConfiguration config) {
        var builder = S3AsyncClient.builder()
                .region(Region.of(config.getString("region")))
                .credentialsProvider(getCredentialsProvider(config))
                .forcePathStyle(config.getBoolean("pathStyleAccess"));

        if (config.containsKey("endpoint")) {
            builder.endpointOverride(URI.create(config.getString("endpoint")));
        }

        // Recent SDK versions send by default CRC checksums (with aws-chunked uploads) which are not supported by
        // all S3-compatible servers. Keep the SDK default only when talking to AWS, unless configured otherwise.
        var defaultChecksums = config.containsKey("endpoint") ? CHECKSUMS_WHEN_REQUIRED : CHECKSUMS_WHEN_SUPPORTED;
        if (CHECKSUMS_WHEN_REQUIRED.equals(config.getString("checksums", defaultChecksums))) {
            builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        } else {
            builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED);
        }

        return builder.build();
    }

    private static AwsCredentialsProvider getCredentialsProvider(YConfiguration config) {
        if (config.containsKey("accessKeyId")) {
            var credentials = AwsBasicCredentials.create(
                    config.getString("accessKeyId"),
                    config.getString("secretAccessKey"));
            return StaticCredentialsProvider.create(credentials);
        } else {
            // Environment variables, system properties, ~/.aws/credentials, container or instance roles, ...
            return DefaultCredentialsProvider.builder().build();
        }
    }

    /**
     * Verifies in the background that the bucket can be accessed. It does not fail the Yamcs start, as the S3 service
     * may only temporarily be unavailable.
     */
    private static void checkAccessible(S3Bucket bucket) {
        bucket.checkAccessibleAsync().whenComplete((res, err) -> {
            if (err == null) {
                log.info("S3 bucket '{}' accessible as Yamcs bucket '{}'", bucket.getS3BucketName(),
                        bucket.getName());
            } else {
                log.warn("Cannot access S3 bucket '{}' (Yamcs bucket '{}'): {}", bucket.getS3BucketName(),
                        bucket.getName(), S3Bucket.unwrap(err).getMessage());
            }
        });
    }
}
