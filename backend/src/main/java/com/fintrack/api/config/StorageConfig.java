package com.fintrack.api.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3-compatible object storage for receipts.
 * <p>
 * Points at MinIO locally and at real S3 or Cloudflare R2 in production - the client code is
 * the same either way, which is the reason for choosing an S3-compatible store rather than
 * anything bespoke.
 */
@Configuration
@ConditionalOnProperty(name = "fintrack.storage.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class StorageConfig {

    private final StorageProperties properties;

    /**
     * @param endpoint       where the API reaches the store; inside compose this is a service name
     * @param publicEndpoint where a *browser* reaches it. Presigned URLs are signed against
     *                       this, because a hostname that only resolves on the container
     *                       network is useless to the user's browser - and the signature
     *                       covers the host, so it cannot be rewritten afterwards.
     */
    @Validated
    @ConfigurationProperties(prefix = "fintrack.storage")
    public record StorageProperties(
            @NotBlank String endpoint,
            String publicEndpoint,
            @NotBlank String bucket,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            String region
    ) {
        public StorageProperties {
            if (region == null || region.isBlank()) {
                // MinIO ignores the region, but the SDK refuses to build a client without one.
                region = "us-east-1";
            }
            if (publicEndpoint == null || publicEndpoint.isBlank()) {
                publicEndpoint = endpoint;
            }
        }
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials())
                // MinIO serves buckets as a path, not a subdomain: virtual-host addressing
                // would resolve to bucket.localhost, which does not exist.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /** Separate client, bound to the browser-visible host, used only to sign download URLs. */
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.publicEndpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }

    /**
     * Creates the bucket if it is missing, so a fresh checkout works after {@code compose up}
     * without a manual setup step. Idempotent, and failure is logged rather than fatal -
     * losing receipts should not stop the rest of the application starting.
     */
    @Bean
    public StorageInitialiser storageInitialiser(S3Client client) {
        return new StorageInitialiser(client, properties.bucket());
    }

    @RequiredArgsConstructor
    @Slf4j
    public static class StorageInitialiser {

        private final S3Client client;
        private final String bucket;

        @PostConstruct
        public void ensureBucketExists() {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                log.debug("Storage bucket {} is present", bucket);
            } catch (S3Exception ex) {   // covers NoSuchBucketException, its subclass
                try {
                    client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("Created storage bucket {}", bucket);
                } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
                    // Another instance won the race. Nothing to do.
                } catch (RuntimeException createFailure) {
                    log.warn("Could not create storage bucket {}; receipt uploads will fail",
                            bucket, createFailure);
                }
            }
        }
    }
}
