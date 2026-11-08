package com.dsh.workbench.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Maintains blob_refs from accepted file-table changes. */
@Service
public class BlobRefIndex {
  private final JdbcTemplate jdbc;

  public BlobRefIndex(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void apply(String userId, String tableName, String op, JsonNode payload, long updatedAt) {
    if (!"file".equals(tableName) || payload == null || !payload.isObject()) return;
    String fileId = text(payload, "id");
    String sha = text(payload, "sha256");
    if (fileId == null) return;
    boolean deleted = "delete".equals(op) || (payload.has("deleted_at") && !payload.get("deleted_at").isNull());
    if (sha != null && sha.matches("[0-9a-f]{64}")) {
      jdbc.update(
          "INSERT INTO blob_refs(user_id,file_id,sha256,deleted,updated_at) VALUES(?,?,?,?,?) " +
              "ON CONFLICT(user_id,file_id) DO UPDATE SET sha256=EXCLUDED.sha256, deleted=EXCLUDED.deleted, updated_at=EXCLUDED.updated_at",
          userId, fileId, sha, deleted, updatedAt);
      return;
    }
    if (deleted) {
      jdbc.update("UPDATE blob_refs SET deleted=true, updated_at=? WHERE user_id=? AND file_id=?",
          updatedAt, userId, fileId);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return null;
    String text = value.asText();
    return text.isBlank() ? null : text;
  }
}
