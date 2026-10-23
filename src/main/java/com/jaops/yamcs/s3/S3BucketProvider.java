package com.jaops.yamcs.s3;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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

/**
 * Provides Yamcs buckets backed by S3 (or S3-compatible) buckets.
 * <p>
 * Each provider entry creates one S3 client which is shared by all the buckets of that entry.
 */
public class S3BucketProvider implements BucketProvider {

    public static final BucketLocation LOCATION = new BucketLocation("s3", "S3");

    private static final Log log = new Log(S3BucketProvider.class);

    static final String CHECKSUMS_WHEN_SUPPORTED = "WHEN_SUPPORTED";
    static final String CHECKSUMS_WHEN_REQUIRED = "WHEN_REQUIRED";

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

        var spec = new Spec();
        spec.addOption("endpoint", OptionType.STRING);
        spec.addOption("region", OptionType.STRING).withRequired(true);
        spec.addOption("accessKeyId", OptionType.STRING);
        spec.addOption("secretAccessKey", OptionType.STRING).withSecret(true);
        spec.addOption("pathStyleAccess", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("checksums", OptionType.STRING)
                .withChoices(CHECKSUMS_WHEN_SUPPORTED, CHECKSUMS_WHEN_REQUIRED);
        spec.addOption("buckets", OptionType.LIST)
                .withRequired(true)
                .withElementType(OptionType.MAP)
                .withSpec(bucketSpec);
        spec.requireTogether("accessKeyId", "secretAccessKey");
        return spec;
    }

    @Override
    public List<Bucket> loadBuckets(YConfiguration config) throws IOException {
        var client = createClient(config);

        var buckets = new ArrayList<Bucket>();
        for (var bucketConfig : config.getConfigList("buckets")) {
            var s3BucketName = bucketConfig.getString("name");
            var localName = bucketConfig.getString("localName", s3BucketName);
            var prefix = bucketConfig.getString("prefix", "");

            var bucket = new S3Bucket(localName, client, s3BucketName, prefix);
            checkAccessible(bucket);
            buckets.add(bucket);
        }
        return buckets;
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
