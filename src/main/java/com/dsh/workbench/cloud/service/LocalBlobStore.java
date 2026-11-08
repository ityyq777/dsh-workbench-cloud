package com.dsh.workbench.cloud.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class LocalBlobStore implements BlobStore {
  private final Path root;

  public LocalBlobStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  private Path resolve(String key) {
    Path path = root.resolve(key).normalize();
    if (!path.startsWith(root)) throw new IllegalArgumentException("invalid storage key");
    return path;
  }

  @Override
  public void put(String key, Path file, long size, String contentType) throws IOException {
    Path target = resolve(key);
    Files.createDirectories(target.getParent());
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
    Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public BlobStat stat(String key) throws IOException {
    Path path = resolve(key);
    if (!Files.isRegularFile(path)) return null;
    String etag = "\"local-" + Files.size(path) + "-" + Files.getLastModifiedTime(path).toMillis() + "\"";
    return new BlobStat(Files.size(path), "application/octet-stream", etag);
  }

  @Override
  public BlobStream open(String key, Long start, Long end) throws IOException {
    Path path = resolve(key);
    long total = Files.size(path);
    long from = start == null ? 0 : start;
    long to = end == null ? total - 1 : Math.min(end, total - 1);
    if (from < 0 || from >= total || to < from) throw new IOException("invalid range");
    InputStream stream = Files.newInputStream(path);
    long skip = from;
    while (skip > 0) {
      long n = stream.skip(skip);
      if (n <= 0) break;
      skip -= n;
    }
    return new BlobStream(new LimitedInputStream(stream, to - from + 1), to - from + 1,
        "application/octet-stream", null, from, to);
  }

  @Override
  public URI presignGet(String key, long ttlSeconds) {
    throw new UnsupportedOperationException("local backend has no presigned URL; use ?proxy=1");
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(resolve(key));
  }

  @Override
  public String backend() { return "local"; }

  private static final class LimitedInputStream extends InputStream {
    private final InputStream delegate;
    private long remaining;
    LimitedInputStream(InputStream delegate, long remaining) { this.delegate = delegate; this.remaining = remaining; }
    @Override public int read() throws IOException {
      if (remaining <= 0) return -1;
      int b = delegate.read();
      if (b >= 0) remaining--;
      return b;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) return -1;
      int n = delegate.read(b, off, (int) Math.min(len, remaining));
      if (n > 0) remaining -= n;
      return n;
    }
  }
}
