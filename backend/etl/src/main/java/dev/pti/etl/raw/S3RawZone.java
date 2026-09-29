package dev.pti.etl.raw;

import dev.pti.common.error.TransientInfraException;
import java.nio.file.Files;
import java.nio.file.Path;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** {@link RawZone} on SeaweedFS (compose, k3d) through the AWS SDK that Spring Cloud AWS configures. */
public class S3RawZone implements RawZone {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3RawZone(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public String key(String relative) {
        return prefix + relative;
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw transientOrFatal(e);
        } catch (SdkClientException e) {
            throw new TransientInfraException("Raw zone unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public void put(String key, Path file) {
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/zip"), RequestBody.fromFile(file));
        } catch (S3Exception e) {
            throw transientOrFatal(e);
        } catch (SdkClientException e) {
            throw new TransientInfraException("Raw zone unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            Files.deleteIfExists(target);
            Files.createDirectories(
                    java.util.Objects.requireNonNull(target.toAbsolutePath().getParent()));
            s3.getObject(b -> b.bucket(bucket).key(key), ResponseTransformer.toFile(target));
        } catch (NoSuchKeyException e) {
            throw new RawObjectMissingException(key);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new RawObjectMissingException(key);
            }
            throw transientOrFatal(e);
        } catch (SdkClientException e) {
            throw new TransientInfraException("Raw zone unreachable: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 5xx and throttling are worth retrying; 403 and other 4xx are configuration errors. */
    private static RuntimeException transientOrFatal(S3Exception e) {
        if (e.statusCode() >= 500 || e.statusCode() == 429) {
            return new TransientInfraException("Raw zone error " + e.statusCode() + ": " + e.getMessage(), e);
        }
        return new dev.pti.common.error.FatalException("Raw zone refused the request: " + e.getMessage(), e);
    }
}
