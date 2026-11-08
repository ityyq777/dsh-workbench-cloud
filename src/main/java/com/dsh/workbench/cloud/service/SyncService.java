package com.dsh.workbench.cloud.service;

import com.dsh.workbench.cloud.config.CloudProperties;
import com.dsh.workbench.cloud.util.CanonicalJson;
import com.dsh.workbench.cloud.util.Ids;
import com.dsh.workbench.cloud.util.Json;
import com.dsh.workbench.cloud.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class SyncService {
  private static final Pattern TABLE_RE = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
  private static final int MAX_PUSH_ITEMS = 500;
  private static final int MAX_PAYLOAD_BYTES = 1_000_000;
  private static final int MAX_FULL_ROWS = 10_000;
  private static final int MAX_FULL_BYTES = 16 * 1024 * 1024;
  private static final int MAX_SNAPSHOT_ROWS = 20_000;
  private static final int MAX_PULL_LIMIT = 1_000;

  private final JdbcTemplate jdbc;
  private final CloudProperties properties;
  private final ObjectMapper mapper;
  private final BlobRefIndex blobRefIndex;

  public SyncService(JdbcTemplate jdbc, CloudProperties properties, ObjectMapper mapper, BlobRefIndex blobRefIndex) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.mapper = mapper;
    this.blobRefIndex = blobRefIndex;
  }

  @Transactional
  public Map<String, Object> open(String userId, String deviceIdRaw, JsonNode protocolRaw) {
    if (protocolRaw == null || protocolRaw.asLong(Long.MIN_VALUE) != 2) {
      throw new ApiException(426, "protocol_required", "protocol v2 required");
    }
    String deviceId = deviceIdRaw == null ? "" : deviceIdRaw.trim();
    if (deviceId.isEmpty() || deviceId.length() > 200) {
      throw new ApiException(400, "invalid_device", "device_id required");
    }
    long now = System.currentTimeMillis();
    jdbc.update("DELETE FROM sync_sessions WHERE expires_at<=?", now);
    List<Map<String, Object>> existing = jdbc.queryForList(
        "SELECT session_id,device_id,expires_at FROM sync_sessions WHERE user_id=?", userId);
    if (!existing.isEmpty()) {
      Map<String, Object> cur = existing.get(0);
      if (!Objects.equals(String.valueOf(cur.get("device_id")), deviceId)) {
        long retry = Math.max(1, ((Number) cur.get("expires_at")).longValue() - now);
        throw new ApiException(409, "sync_locked", "another device is syncing", Map.of("retry_after_ms", retry));
      }
      jdbc.update("DELETE FROM sync_sessions WHERE user_id=?", userId);
    }
    long ttl = Math.max(10_000, Math.min(properties.getSync().getSessionTtlSeconds() * 1000L, 600_000));
    long expires = now + ttl;
    String sid = Ids.uuidv7();
    jdbc.update("INSERT INTO sync_sessions(user_id,session_id,device_id,opened_at,expires_at) VALUES(?,?,?,?,?)",
        userId, sid, deviceId, now, expires);
    Map<String, Object> tables = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbc.queryForList(
        "SELECT table_name,version,mode FROM sync_table_state WHERE user_id=? ORDER BY table_name", userId)) {
      tables.put(String.valueOf(row.get("table_name")), Json.map(
          "version", ((Number) row.get("version")).longValue(),
          "mode", "tree".equals(row.get("mode")) ? "tree" : "row"));
    }
    return Json.map("session_id", sid, "server_time", now, "expires_at", expires, "tables", tables);
  }

  @Transactional
  public void touch(String userId, String deviceId, String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new ApiException(409, "session_required", "session_id required");
    }
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT session_id,device_id,expires_at FROM sync_sessions WHERE user_id=?", userId);
    if (rows.isEmpty() || !Objects.equals(String.valueOf(rows.get(0).get("device_id")), deviceId)
        || !Objects.equals(String.valueOf(rows.get(0).get("session_id")), sessionId)) {
      throw new ApiException(409, "session_mismatch", "sync session not found");
    }
    long now = System.currentTimeMillis();
    long expires = ((Number) rows.get(0).get("expires_at")).longValue();
    if (expires <= now) {
      jdbc.update("DELETE FROM sync_sessions WHERE user_id=?", userId);
      throw new ApiException(409, "session_expired", "sync session expired");
    }
    long ttl = Math.max(10_000, Math.min(properties.getSync().getSessionTtlSeconds() * 1000L, 600_000));
    jdbc.update("UPDATE sync_sessions SET expires_at=? WHERE user_id=?", now + ttl, userId);
  }

  public record PushItem(long clientSeq, String tableName, String rowId, String op, JsonNode payload,
                         String canonical, long updatedAt) {}

  @Transactional
  public Map<String, Object> push(String userId, String deviceId, String clientEpoch, String sessionId,
                                  JsonNode changes) {
    if (changes == null || !changes.isArray()) {
      throw new ApiException(400, "invalid_changes", "changes must be an array");
    }
    if (changes.size() > MAX_PUSH_ITEMS) {
      throw new ApiException(413, "too_many_changes", "too many changes");
    }
    touch(userId, deviceId, sessionId);
    if (clientEpoch == null || clientEpoch.isBlank()) {
      throw new ApiException(400, "epoch_required", "client_epoch required");
    }

    long acceptedUpTo = 0;
    List<Map<String, Object>> results = new ArrayList<>();
    List<PushItem> accepted = new ArrayList<>();

    for (JsonNode raw : changes) {
      JsonNode item = raw != null && raw.isObject() ? raw : mapper.createObjectNode();
      Long clientSeq = Json.longValue(item.get("client_seq"));
      String table = text(item, "table_name");
      String rowId = text(item, "row_id");
      String op = text(item, "op");
      Long updatedAt = Json.longValue(item.get("updated_at"));
      JsonNode payload = item.get("payload");

      if (clientSeq == null || clientSeq < 1) {
        results.add(result(0, table, rowId, "invalid", "invalid client_seq", null));
        continue;
      }
      acceptedUpTo = Math.max(acceptedUpTo, clientSeq);
      if (table == null || !TABLE_RE.matcher(table).matches()) {
        results.add(result(clientSeq, table, rowId, "invalid", "invalid table_name", null));
        continue;
      }
      if (rowId == null || rowId.isBlank() || rowId.length() > 200) {
        results.add(result(clientSeq, table, rowId, "invalid", "invalid row_id", null));
        continue;
      }
      if (!"upsert".equals(op) && !"delete".equals(op)) {
        results.add(result(clientSeq, table, rowId, "invalid", "invalid op", null));
        continue;
      }
      if (updatedAt == null) {
        results.add(result(clientSeq, table, rowId, "invalid", "invalid updated_at", null));
        continue;
      }
      if (payload == null || !payload.isObject()) {
        results.add(result(clientSeq, table, rowId, "invalid", "payload must be an object", null));
        continue;
      }
      if (!rowId.equals(payload.path("id").asText())) {
        results.add(result(clientSeq, table, rowId, "invalid", "payload.id must equal row_id", null));
        continue;
      }
      Long payloadUpdatedAt = Json.longValue(payload.get("updated_at"));
      if (payloadUpdatedAt == null || !payloadUpdatedAt.equals(updatedAt)) {
        results.add(result(clientSeq, table, rowId, "invalid", "payload.updated_at must equal updated_at", null));
        continue;
      }
      String canonical = CanonicalJson.stringify(payload);
      if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
        results.add(result(clientSeq, table, rowId, "invalid", "payload too large", null));
        continue;
      }

      List<Map<String, Object>> dupRows = jdbc.queryForList(
          "SELECT 1 FROM change_log WHERE user_id=? AND device_id=? AND client_epoch=? AND client_seq=?",
          userId, deviceId, clientEpoch, clientSeq);
      boolean dup = !dupRows.isEmpty();
      List<Map<String, Object>> currentRows = jdbc.queryForList(
          "SELECT payload::text AS payload,updated_at FROM row_states WHERE user_id=? AND table_name=? AND row_id=?",
          userId, table, rowId);
      JsonNode current = currentRows.isEmpty() ? null : parse(currentRows.get(0).get("payload"));
      if (dup) {
        results.add(result(clientSeq, table, rowId, "duplicate", null, current));
        continue;
      }
      if (!currentRows.isEmpty()) {
        long curUpdated = ((Number) currentRows.get(0).get("updated_at")).longValue();
        if (curUpdated > updatedAt) {
          results.add(result(clientSeq, table, rowId, "conflict", null, current));
          continue;
        }
        if (curUpdated == updatedAt) {
          boolean same = current != null && canonical.equals(CanonicalJson.stringify(current));
          results.add(result(clientSeq, table, rowId, same ? "equal" : "conflict", null, current));
          continue;
        }
      }
      boolean deleted = payload.has("deleted_at") && !payload.get("deleted_at").isNull();
      accepted.add(new PushItem(clientSeq, table, rowId, op, payload, canonical, updatedAt));
      results.add(result(clientSeq, table, rowId, "accepted", null, null));
    }

    for (PushItem item : accepted) {
      jdbc.update("INSERT INTO row_states(user_id,table_name,row_id,payload,deleted,updated_at) " +
              "VALUES(?,?,?,?::jsonb,?,?) ON CONFLICT(user_id,table_name,row_id) DO UPDATE SET " +
              "payload=EXCLUDED.payload, deleted=EXCLUDED.deleted, updated_at=EXCLUDED.updated_at",
          userId, item.tableName(), item.rowId(), item.canonical(), deleted(item.payload()), item.updatedAt());
      jdbc.update("INSERT INTO change_log(user_id,device_id,client_epoch,client_seq,table_name,row_id,op,payload,updated_at) " +
              "VALUES(?,?,?,?,?,?,?,?::jsonb,?)",
          userId, deviceId, clientEpoch, item.clientSeq(), item.tableName(), item.rowId(), item.op(),
          item.canonical(), item.updatedAt());
      blobRefIndex.apply(userId, item.tableName(), item.op(), item.payload(), item.updatedAt());
    }

    return Json.map("ok", true, "accepted_up_to", acceptedUpTo, "results", results,
        "server_time", System.currentTimeMillis());
  }

  public Map<String, Object> pull(String userId, String deviceId, String sessionId, JsonNode cursorsRaw,
                                  JsonNode limitRaw) {
    touch(userId, deviceId, sessionId);
    Map<String, Long> cursors = new LinkedHashMap<>();
    if (cursorsRaw != null && cursorsRaw.isObject()) {
      cursorsRaw.fields().forEachRemaining(e -> {
        if (TABLE_RE.matcher(e.getKey()).matches()) {
          Long n = Json.longValue(e.getValue());
          if (n != null && n >= 0) cursors.put(e.getKey(), n);
        }
      });
    }
    long limit = Json.longValue(limitRaw) == null ? 200 : Math.max(1, Math.min(MAX_PULL_LIMIT, Json.longValue(limitRaw)));
    List<Map<String, Object>> merged = new ArrayList<>();
    for (Map.Entry<String, Long> cursor : cursors.entrySet()) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT server_seq,device_id,client_epoch,table_name,row_id,op,payload::text AS payload,updated_at " +
              "FROM change_log WHERE user_id=? AND table_name=? AND server_seq>? ORDER BY server_seq ASC LIMIT ?",
          userId, cursor.getKey(), cursor.getValue(), limit + 1);
      for (Map<String, Object> row : rows) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("server_seq", ((Number) row.get("server_seq")).longValue());
        entry.put("device_id", row.get("device_id"));
        entry.put("client_epoch", row.get("client_epoch"));
        entry.put("table_name", row.get("table_name"));
        entry.put("row_id", row.get("row_id"));
        entry.put("op", row.get("op"));
        entry.put("payload", parse(row.get("payload")));
        entry.put("updated_at", ((Number) row.get("updated_at")).longValue());
        merged.add(entry);
      }
    }
    merged.sort(Comparator.comparingLong(e -> ((Number) e.get("server_seq")).longValue()));
    List<Map<String, Object>> entries = new ArrayList<>(merged.subList(0, Math.min(merged.size(), (int) limit)));
    Map<String, Long> nextCursors = new LinkedHashMap<>(cursors);
    for (Map<String, Object> entry : entries) {
      String table = String.valueOf(entry.get("table_name"));
      long seq = ((Number) entry.get("server_seq")).longValue();
      nextCursors.merge(table, seq, Math::max);
    }
    boolean hasMore = merged.size() > entries.size();
    if (!hasMore) {
      for (String table : cursors.keySet()) {
        List<Map<String, Object>> more = jdbc.queryForList(
            "SELECT 1 FROM change_log WHERE user_id=? AND table_name=? AND server_seq>? LIMIT 1",
            userId, table, nextCursors.getOrDefault(table, 0L));
        if (!more.isEmpty()) {
          hasMore = true;
          break;
        }
      }
    }
    Long min = jdbc.queryForObject("SELECT MIN(server_seq) FROM change_log WHERE user_id=?", Long.class, userId);
    return Json.map("ok", true, "entries", entries, "next_cursors", nextCursors,
        "has_more", hasMore, "min_seq", min == null ? 0 : min, "server_time", System.currentTimeMillis());
  }

  public Map<String, Object> snapshot(String userId, String deviceId, String sessionId, String tableRaw) {
    touch(userId, deviceId, sessionId);
    String table = assertTable(tableRaw);
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT payload::text AS payload FROM row_states WHERE user_id=? AND table_name=? ORDER BY row_id ASC LIMIT ?",
        userId, table, MAX_SNAPSHOT_ROWS + 1);
    if (rows.size() > MAX_SNAPSHOT_ROWS) throw new ApiException(413, "snapshot_too_large", "snapshot too large");
    List<JsonNode> payloads = new ArrayList<>();
    for (Map<String, Object> row : rows) payloads.add(parse(row.get("payload")));
    List<Map<String, Object>> state = jdbc.queryForList(
        "SELECT version,mode FROM sync_table_state WHERE user_id=? AND table_name=?", userId, table);
    long version = state.isEmpty() ? 0 : ((Number) state.get(0).get("version")).longValue();
    String mode = state.isEmpty() || !"tree".equals(state.get(0).get("mode")) ? "row" : "tree";
    Long cursor = jdbc.queryForObject(
        "SELECT MAX(server_seq) FROM change_log WHERE user_id=? AND table_name=?", Long.class, userId, table);
    return Json.map("ok", true, "table_name", table, "rows", payloads,
        "cursor", cursor == null ? 0 : cursor, "version", version, "mode", mode,
        "server_time", System.currentTimeMillis());
  }

  @Transactional
  public Map<String, Object> full(String userId, String deviceId, String sessionId, String tableRaw,
                                  JsonNode baseVersionRaw, JsonNode rowsRaw) {
    touch(userId, deviceId, sessionId);
    String table = assertTable(tableRaw);
    Long baseVersion = Json.longValue(baseVersionRaw);
    if (baseVersion == null) throw new ApiException(400, "invalid_base_version", "invalid base_version");
    if (rowsRaw == null || !rowsRaw.isArray()) throw new ApiException(400, "invalid_rows", "rows must be an array");
    if (rowsRaw.size() > MAX_FULL_ROWS) throw new ApiException(413, "full_too_large", "too many rows");

    List<FullRow> rows = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    int bytes = 0;
    for (JsonNode raw : rowsRaw) {
      if (raw == null || !raw.isObject()) throw new ApiException(400, "invalid_row", "row must be an object");
      String id = text(raw, "id");
      Long updatedAt = Json.longValue(raw.get("updated_at"));
      if (id == null || id.isBlank() || id.length() > 200 || !seen.add(id)) {
        throw new ApiException(400, "invalid_row", "invalid or duplicate row id");
      }
      if (updatedAt == null) throw new ApiException(400, "invalid_row", "invalid row updated_at");
      if (!id.equals(raw.path("id").asText())) throw new ApiException(400, "invalid_row", "row.id mismatch");
      String canonical = CanonicalJson.stringify(raw);
      bytes += canonical.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_FULL_BYTES) throw new ApiException(413, "full_too_large", "full payload too large");
      boolean deleted = raw.has("deleted_at") && !raw.get("deleted_at").isNull();
      rows.add(new FullRow(id, canonical, deleted, updatedAt));
    }

    List<Map<String, Object>> state = jdbc.queryForList(
        "SELECT version FROM sync_table_state WHERE user_id=? AND table_name=?", userId, table);
    long current = state.isEmpty() ? 0 : ((Number) state.get(0).get("version")).longValue();
    if (current != baseVersion) {
      throw new ApiException(409, "version_conflict", "table version changed", Map.of("version", current));
    }
    jdbc.update("DELETE FROM row_states WHERE user_id=? AND table_name=?", userId, table);
    for (FullRow row : rows) {
      jdbc.update("INSERT INTO row_states(user_id,table_name,row_id,payload,deleted,updated_at) " +
              "VALUES(?,?,?,?::jsonb,?,?)",
          userId, table, row.id(), row.payload(), row.deleted(), row.updatedAt());
    }
    long nextVersion = current + 1;
    jdbc.update("INSERT INTO sync_table_state(user_id,table_name,version,mode,updated_at) VALUES(?,?,?,?,?) " +
            "ON CONFLICT(user_id,table_name) DO UPDATE SET version=EXCLUDED.version, mode=EXCLUDED.mode, updated_at=EXCLUDED.updated_at",
        userId, table, nextVersion, "tree", System.currentTimeMillis());
    jdbc.update("INSERT INTO change_log(user_id,device_id,client_epoch,client_seq,table_name,row_id,op,payload,updated_at) " +
            "VALUES(?,?,?,?,?,?,?,?::jsonb,?)",
        userId, deviceId, "reset-" + Ids.uuidv7(), 0, table, "*", "reset",
        CanonicalJson.stringify(mapper.createObjectNode().put("version", nextVersion)), System.currentTimeMillis());
    Long cursor = jdbc.queryForObject(
        "SELECT MAX(server_seq) FROM change_log WHERE user_id=? AND table_name=?", Long.class, userId, table);
    return Json.map("ok", true, "table_name", table, "version", nextVersion,
        "cursor", cursor == null ? 0 : cursor, "server_time", System.currentTimeMillis());
  }

  @Transactional
  public Map<String, Object> close(String userId, String deviceId, String sessionId, boolean success,
                                   JsonNode cursorsRaw) {
    if (sessionId != null && !sessionId.isBlank()) {
      jdbc.update("DELETE FROM sync_sessions WHERE user_id=? AND session_id=?", userId, sessionId);
    }
    if (success) {
      Map<String, Long> cursors = new LinkedHashMap<>();
      if (cursorsRaw != null && cursorsRaw.isObject()) {
        cursorsRaw.fields().forEachRemaining(e -> {
          if (TABLE_RE.matcher(e.getKey()).matches()) {
            Long n = Json.longValue(e.getValue());
            if (n != null && n >= 0) cursors.put(e.getKey(), n);
          }
        });
      }
      try {
        jdbc.update("UPDATE devices SET last_sync_at=?, cursors=? WHERE id=? AND user_id=?",
            System.currentTimeMillis(), mapper.writeValueAsString(cursors), deviceId, userId);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return Map.of("ok", true);
  }

  private String assertTable(String tableRaw) {
    String table = tableRaw == null ? "" : tableRaw;
    if (!TABLE_RE.matcher(table).matches()) throw new ApiException(400, "invalid_table", "invalid table name");
    return table;
  }

  private Map<String, Object> result(long clientSeq, String table, String rowId, String status,
                                     String error, JsonNode current) {
    Map<String, Object> out = Json.map("client_seq", clientSeq, "table_name", table, "row_id", rowId, "status", status);
    if (error != null) out.put("error", error);
    if (current != null) out.put("current", current);
    return out;
  }

  private boolean deleted(JsonNode payload) {
    return payload.has("deleted_at") && !payload.get("deleted_at").isNull();
  }

  private JsonNode parse(Object raw) {
    if (raw == null) return null;
    try {
      return mapper.readTree(String.valueOf(raw));
    } catch (Exception e) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    if (node == null) return null;
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return null;
    String text = value.asText();
    return text.isBlank() ? null : text;
  }

  private record FullRow(String id, String payload, boolean deleted, long updatedAt) {}
}
