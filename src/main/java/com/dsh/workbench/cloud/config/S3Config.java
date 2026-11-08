package com.dsh.workbench.cloud.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "wb.blob.backend", havingValue = "s3", matchIfMissing = true)
public class S3Config {
  private static S3Configuration s3Configuration(CloudProperties properties) {
    return S3Configuration.builder()
        .pathStyleAccessEnabled(properties.getBlob().getS3().isForcePathStyle())
        .build();
  }

  @Bean
  public S3Client s3Client(CloudProperties properties) {
    var s3 = properties.getBlob().getS3();
    return S3Client.builder()
        .endpointOverride(URI.create(s3.getEndpoint()))
        .region(Region.of(s3.getRegion()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3.getAccessKeyId(), s3.getSecretAccessKey())))
        .serviceConfiguration(s3Configuration(properties))
        .build();
  }

  @Bean
  public S3Presigner s3Presigner(CloudProperties properties) {
    var s3 = properties.getBlob().getS3();
    return S3Presigner.builder()
        .endpointOverride(URI.create(s3.getEndpoint()))
        .region(Region.of(s3.getRegion()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3.getAccessKeyId(), s3.getSecretAccessKey())))
        .serviceConfiguration(s3Configuration(properties))
        .build();
  }
}
