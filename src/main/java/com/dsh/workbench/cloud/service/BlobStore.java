package com.dsh.workbench.cloud.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;

public interface BlobStore {
  void put(String key, Path file, long size, String contentType) throws IOException;

  BlobStat stat(String key) throws IOException;

  BlobStream open(String key, Long start, Long end) throws IOException;

  URI presignGet(String key, long ttlSeconds);

  void delete(String key) throws IOException;

  String backend();

  record BlobStat(long size, String contentType, String etag) {}

  record BlobStream(InputStream stream, long size, String contentType, String etag, long start, long end) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      stream.close();
    }
  }
}
