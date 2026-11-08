package com.dsh.workbench.cloud.config;

import com.dsh.workbench.cloud.service.BlobStore;
import com.dsh.workbench.cloud.service.LocalBlobStore;
import com.dsh.workbench.cloud.service.S3BlobStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.nio.file.Path;

@Configuration
public class BlobStoreConfig {
  @Bean
  public BlobStore blobStore(CloudProperties properties,
                             org.springframework.beans.factory.ObjectProvider<S3Client> s3Client,
                             org.springframework.beans.factory.ObjectProvider<S3Presigner> presigner) {
    if ("s3".equalsIgnoreCase(properties.getBlob().getBackend())) {
      return new S3BlobStore(s3Client.getObject(), presigner.getObject(), properties);
    }
    return new LocalBlobStore(Path.of(properties.getBlob().getDir()));
  }
}
