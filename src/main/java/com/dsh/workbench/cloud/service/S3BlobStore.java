package com.dsh.workbench.cloud.service;

import com.dsh.workbench.cloud.config.CloudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public class S3BlobStore implements BlobStore {
  private static final Logger log = LoggerFactory.getLogger(S3BlobStore.class);
  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;
  private final String prefix;

  public S3BlobStore(S3Client s3, S3Presigner presigner, CloudProperties properties) {
    this.s3 = s3;
    this.presigner = presigner;
    this.bucket = properties.getBlob().getS3().getBucket();
    String raw = properties.getBlob().getS3().getPrefix();
    this.prefix = raw == null || raw.isBlank() ? "" : (raw.endsWith("/") ? raw : raw + "/");
    ensureBucket();
  }

  private void ensureBucket() {
    try {
      s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        log.info("created S3 bucket {}", bucket);
      } else {
        log.warn("S3 bucket check failed for {}: {}", bucket, e.getMessage());
      }
    } catch (Exception e) {
      log.warn("S3 unavailable at startup: {}", e.getMessage());
    }
  }

  @Override
  public void put(String key, Path file, long size, String contentType) {
    PutObjectRequest.Builder builder = PutObjectRequest.builder()
        .bucket(bucket)
        .key(prefix + key)
        .contentLength(size)
        .contentType(contentType == null ? "application/octet-stream" : contentType);
    s3.putObject(builder.build(), RequestBody.fromFile(file));
  }

  @Override
  public BlobStat stat(String key) {
    try {
      HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(prefix + key).build());
      return new BlobStat(head.contentLength(), head.contentType(), head.eTag());
    } catch (NoSuchKeyException e) {
      return null;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) return null;
      throw e;
    }
  }

  @Override
  public BlobStream open(String key, Long start, Long end) throws IOException {
    GetObjectRequest.Builder builder = GetObjectRequest.builder().bucket(bucket).key(prefix + key);
    if (start != null || end != null) {
      String range = "bytes=" + (start == null ? 0 : start) + "-" + (end == null ? "" : end);
      builder.range(range);
    }
    ResponseInputStream<GetObjectResponse> stream = s3.getObject(builder.build());
    GetObjectResponse response = stream.response();
    long from = start == null ? 0 : start;
    long to = end == null ? response.contentLength() - 1 : end;
    return new BlobStream(stream, response.contentLength(), response.contentType(), response.eTag(), from, to);
  }

  @Override
  public URI presignGet(String key, long ttlSeconds) {
    GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(prefix + key).build();
    GetObjectPresignRequest request = GetObjectPresignRequest.builder()
        .signatureDuration(Duration.ofSeconds(ttlSeconds))
        .getObjectRequest(get)
        .build();
    return URI.create(presigner.presignGetObject(request).url().toString());
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(b -> b.bucket(bucket).key(prefix + key));
  }

  @Override
  public String backend() { return "s3"; }
}
