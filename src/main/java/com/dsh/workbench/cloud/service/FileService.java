package com.dsh.workbench.cloud.service;

import com.dsh.workbench.cloud.config.CloudProperties;
import com.dsh.workbench.cloud.util.Json;
import com.dsh.workbench.cloud.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FileService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileService.class);
  private final JdbcTemplate jdbc;
  private final BlobStore blobs;
  private final CloudProperties properties;

  public FileService(JdbcTemplate jdbc, BlobStore blobs, CloudProperties properties) {
    this.jdbc = jdbc;
    this.blobs = blobs;
    this.properties = properties;
  }

  public record PutResult(boolean deduped, long size) {}

  public PutResult put(String userId, String sha256, InputStream input, long contentLength, String contentType)
      throws IOException {
    Path temp = Files.createTempFile("wb-upload-", ".part");
    long total = 0;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (OutputStream out = Files.newOutputStream(temp)) {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = input.read(buffer)) >= 0) {
          total += n;
          if (total > properties.getFiles().getMaxBytes()) {
            throw new ApiException(413, "file_too_large", "file too large");
          }
          digest.update(buffer, 0, n);
          out.write(buffer, 0, n);
        }
      }
      if (contentLength >= 0 && total != contentLength) {
        throw new ApiException(422, "size_mismatch", "Content-Length does not match body");
      }
      String actual = HexFormat.of().formatHex(digest.digest());
      if (!actual.equals(sha256)) {
        throw new ApiException(422, "sha256_mismatch", "sha256 does not match body");
      }

      List<Map<String, Object>> existing = jdbc.queryForList(
          "SELECT size,state FROM blobs WHERE user_id=? AND sha256=?", userId, sha256);
      if (!existing.isEmpty() && "ready".equals(existing.get(0).get("state"))
          && ((Number) existing.get(0).get("size")).longValue() == total) {
        return new PutResult(true, total);
      }

      Long used = jdbc.queryForObject(
          "SELECT COALESCE(SUM(size),0) FROM blobs WHERE user_id=? AND state='ready'", Long.class, userId);
      if (used != null && used + total > properties.getFiles().getQuotaBytes()) {
        throw new ApiException(413, "quota_exceeded", "storage quota exceeded");
      }

      String key = storageKey(userId, sha256);
      try {
        blobs.put(key, temp, total, contentType);
      } catch (Exception e) {
        log.warn("blob store put failed for {}: {}", key, e.toString(), e);
        throw new ApiException(503, "blob_store_unavailable", "blob store unavailable");
      }
      long now = System.currentTimeMillis();
      jdbc.update("INSERT INTO blobs(user_id,sha256,size,mime,backend,storage_key,state,created_at,verified_at,last_access_at) " +
              "VALUES(?,?,?,?,?,?, 'ready', ?,?,?) ON CONFLICT(user_id,sha256) DO UPDATE SET " +
              "size=EXCLUDED.size, mime=EXCLUDED.mime, backend=EXCLUDED.backend, storage_key=EXCLUDED.storage_key, " +
              "state='ready', verified_at=EXCLUDED.verified_at",
          userId, sha256, total, contentType, blobs.backend(), key, now, now, now);
      return new PutResult(false, total);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      log.warn("file upload failed: {}", e.getMessage());
      throw new IOException("upload failed", e);
    } finally {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {
      }
    }
  }

  public Map<String, Object> check(String userId, List<String> hashes) {
    List<String> have = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String sha : hashes) {
      List<Map<String, Object>> one = jdbc.queryForList(
          "SELECT 1 FROM blobs WHERE user_id=? AND sha256=? AND state='ready'", userId, sha);
      if (!one.isEmpty()) have.add(sha); else missing.add(sha);
    }
    return Json.map("ok", true, "have", have, "missing", missing);
  }

  public Map<String, Object> quota(String userId) {
    Long used = jdbc.queryForObject(
        "SELECT COALESCE(SUM(size),0) FROM blobs WHERE user_id=? AND state='ready'", Long.class, userId);
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM blobs WHERE user_id=? AND state='ready'", Long.class, userId);
    return Json.map("ok", true, "used_bytes", used == null ? 0 : used,
        "quota_bytes", properties.getFiles().getQuotaBytes(),
        "blob_count", count == null ? 0 : count);
  }

  public BlobRecord requireBlob(String userId, String sha256) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT size,mime,backend,storage_key,state FROM blobs WHERE user_id=? AND sha256=?", userId, sha256);
    if (rows.isEmpty() || !"ready".equals(rows.get(0).get("state"))) {
      throw new ApiException(404, "blob_not_found", "blob not found");
    }
    Map<String, Object> row = rows.get(0);
    return new BlobRecord(sha256, ((Number) row.get("size")).longValue(),
        row.get("mime") == null ? null : String.valueOf(row.get("mime")),
        String.valueOf(row.get("backend")), String.valueOf(row.get("storage_key")));
  }

  public boolean isReferenced(String userId, String sha256) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT 1 FROM blob_refs WHERE user_id=? AND sha256=? AND deleted=false LIMIT 1", userId, sha256);
    return !rows.isEmpty();
  }

  public BlobStore.BlobStream open(BlobRecord blob, Long start, Long end) throws IOException {
    return blobs.open(blob.storageKey(), start, end);
  }

  public String presign(BlobRecord blob) {
    return blobs.presignGet(blob.storageKey(), 300).toString();
  }

  public void touch(String userId, String sha256) {
    jdbc.update("UPDATE blobs SET last_access_at=? WHERE user_id=? AND sha256=?",
        System.currentTimeMillis(), userId, sha256);
  }

  public String storageKey(String userId, String sha256) {
    return "u/" + userId + "/" + sha256.substring(0, 2) + "/" + sha256;
  }

  public record BlobRecord(String sha256, long size, String mime, String backend, String storageKey) {}
}
