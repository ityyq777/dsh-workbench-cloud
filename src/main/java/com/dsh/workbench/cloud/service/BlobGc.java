package com.dsh.workbench.cloud.service;

import com.dsh.workbench.cloud.config.CloudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BlobGc {
  private static final Logger log = LoggerFactory.getLogger(BlobGc.class);
  private final JdbcTemplate jdbc;
  private final BlobStore blobs;
  private final CloudProperties properties;

  public BlobGc(JdbcTemplate jdbc, BlobStore blobs, CloudProperties properties) {
    this.jdbc = jdbc;
    this.blobs = blobs;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${wb.files.gc-interval-ms:21600000}",
      initialDelayString = "${wb.files.gc-interval-ms:21600000}")
  public void run() {
    if (!properties.getFiles().isGcEnabled()) return;
    long cutoff = System.currentTimeMillis() - properties.getFiles().getGcGraceDays() * 86_400_000L;
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT b.user_id,b.sha256,b.storage_key FROM blobs b " +
            "WHERE b.created_at < ? AND NOT EXISTS (" +
            "SELECT 1 FROM blob_refs r WHERE r.user_id=b.user_id AND r.sha256=b.sha256 AND r.deleted=false" +
            ") LIMIT 200", cutoff);
    for (Map<String, Object> row : rows) {
      String userId = String.valueOf(row.get("user_id"));
      String sha = String.valueOf(row.get("sha256"));
      String key = String.valueOf(row.get("storage_key"));
      try {
        blobs.delete(key);
        jdbc.update("DELETE FROM blob_refs WHERE user_id=? AND sha256=?", userId, sha);
        jdbc.update("DELETE FROM blobs WHERE user_id=? AND sha256=?", userId, sha);
        log.info("gc deleted blob {}/{}", userId, sha);
      } catch (Exception e) {
        log.warn("gc failed for {}/{}: {}", userId, sha, e.getMessage());
      }
    }
  }
}
