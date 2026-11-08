package com.dsh.workbench.cloud.service;

import com.dsh.workbench.cloud.config.CloudProperties;
import com.dsh.workbench.cloud.util.Hex;
import com.dsh.workbench.cloud.util.Ids;
import com.dsh.workbench.cloud.util.Json;
import com.dsh.workbench.cloud.web.ApiException;
import com.dsh.workbench.cloud.web.AuthPrincipal;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SCRYPT_N = 16384;
  private static final int SCRYPT_R = 8;
  private static final int SCRYPT_P = 1;

  private final JdbcTemplate jdbc;
  private final CloudProperties properties;

  public AuthService(JdbcTemplate jdbc, CloudProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  public record Session(String accessToken, String refreshToken, String userId, String deviceId) {}

  @Transactional
  public Session signup(String email, String password, String deviceId, String deviceName) {
    if (email == null || email.isBlank() || password == null || password.isEmpty()) {
      throw new ApiException(400, "invalid_request", "email/password required");
    }
    String id = Ids.uuidv7();
    try {
      jdbc.update("INSERT INTO users(id,email,pass_hash,created_at) VALUES(?,?,?,?)",
          id, email, hashPassword(password), System.currentTimeMillis());
    } catch (DuplicateKeyException e) {
      throw new ApiException(409, "email_exists", "email exists");
    }
    return issueSession(id, normalizeDevice(deviceId), deviceName);
  }

  @Transactional
  public Session login(String email, String password, String deviceId, String deviceName) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id,pass_hash FROM users WHERE email=?", email);
    if (rows.isEmpty()) throw new ApiException(401, "bad_credentials", "bad credentials");
    Map<String, Object> row = rows.get(0);
    if (!verifyPassword(password == null ? "" : password, String.valueOf(row.get("pass_hash")))) {
      throw new ApiException(401, "bad_credentials", "bad credentials");
    }
    return issueSession(String.valueOf(row.get("id")), normalizeDevice(deviceId), deviceName);
  }

  @Transactional
  public Session refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) return null;
    String hash = hashToken(refreshToken);
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT token_hash,user_id,device_id,expires_at,replaced_by FROM refresh_tokens WHERE token_hash=?",
        hash);
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.get(0);
    long expiresAt = ((Number) row.get("expires_at")).longValue();
    if (expiresAt < System.currentTimeMillis()) return null;
    if (row.get("replaced_by") != null) {
      // Reuse of a rotated token: revoke the whole device chain.
      String userId = String.valueOf(row.get("user_id"));
      String deviceId = String.valueOf(row.get("device_id"));
      revokeDevice(userId, deviceId);
      return null;
    }
    String userId = String.valueOf(row.get("user_id"));
    String deviceId = String.valueOf(row.get("device_id"));
    Session fresh = issueSession(userId, deviceId, null);
    jdbc.update("UPDATE refresh_tokens SET replaced_by=? WHERE token_hash=?", hashToken(fresh.refreshToken()), hash);
    return fresh;
  }

  public AuthPrincipal authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) return null;
    String token = authorization.substring(7).trim();
    if (token.isEmpty()) return null;
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT user_id,device_id,expires_at FROM access_tokens WHERE token_hash=?", hashToken(token));
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.get(0);
    if (((Number) row.get("expires_at")).longValue() <= System.currentTimeMillis()) {
      jdbc.update("DELETE FROM access_tokens WHERE token_hash=?", hashToken(token));
      return null;
    }
    return new AuthPrincipal(String.valueOf(row.get("user_id")), String.valueOf(row.get("device_id")));
  }

  public Map<String, Object> me(AuthPrincipal principal) {
    List<Map<String, Object>> users = jdbc.queryForList(
        "SELECT id,email,created_at FROM users WHERE id=?", principal.userId());
    if (users.isEmpty()) throw new ApiException(401, "unauthorized", "unauthorized");
    List<Map<String, Object>> devices = jdbc.queryForList(
        "SELECT id,name,last_sync_at,cursors FROM devices WHERE user_id=? ORDER BY id", principal.userId());
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> d : devices) {
      out.add(Json.map(
          "id", d.get("id"),
          "name", d.get("name"),
          "last_sync_at", d.get("last_sync_at"),
          "cursors", d.get("cursors")));
    }
    Map<String, Object> user = users.get(0);
    return Json.map("ok", true,
        "user", Json.map("id", user.get("id"), "email", user.get("email"), "created_at", user.get("created_at")),
        "devices", out);
  }

  @Transactional
  public void revokeDevice(String userId, String deviceId) {
    jdbc.update("DELETE FROM refresh_tokens WHERE user_id=? AND device_id=?", userId, deviceId);
    jdbc.update("DELETE FROM access_tokens WHERE user_id=? AND device_id=?", userId, deviceId);
    jdbc.update("DELETE FROM devices WHERE user_id=? AND id=?", userId, deviceId);
  }

  private Session issueSession(String userId, String deviceId, String deviceName) {
    long now = System.currentTimeMillis();
    jdbc.update("DELETE FROM access_tokens WHERE expires_at<=?", now);
    String accessToken = randomToken(24);
    String refreshToken = randomToken(32);
    long accessExpires = now + properties.getAuth().getAccessTtlSeconds() * 1000L;
    long refreshExpires = now + properties.getAuth().getRefreshTtlSeconds() * 1000L;
    jdbc.update("INSERT INTO access_tokens(token_hash,user_id,device_id,expires_at) VALUES(?,?,?,?)",
        hashToken(accessToken), userId, deviceId, accessExpires);
    jdbc.update("INSERT INTO refresh_tokens(token_hash,user_id,device_id,expires_at) VALUES(?,?,?,?)",
        hashToken(refreshToken), userId, deviceId, refreshExpires);
    jdbc.update("INSERT INTO devices(id,user_id,name,last_cursor) VALUES(?,?,?,0) " +
            "ON CONFLICT(id,user_id) DO UPDATE SET name=COALESCE(EXCLUDED.name, devices.name)",
        deviceId, userId, deviceName);
    return new Session(accessToken, refreshToken, userId, deviceId);
  }

  private static String normalizeDevice(String deviceId) {
    return deviceId == null || deviceId.isBlank() ? Ids.uuidv7() : deviceId;
  }

  private static String randomToken(int bytes) {
    byte[] data = new byte[bytes];
    RANDOM.nextBytes(data);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }

  public static String hashToken(String token) {
    try {
      return Hex.encode(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hashPassword(String password) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[] hash = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt,
        SCRYPT_N, SCRYPT_R, SCRYPT_P, 32);
    return "scrypt_" + SCRYPT_N + "_" + SCRYPT_R + "_" + SCRYPT_P + "_" + Hex.encode(salt) + "_" + Hex.encode(hash);
  }

  public static boolean verifyPassword(String password, String stored) {
    try {
      String[] parts = stored.split("[_$]");
      if (parts.length != 6 || !"scrypt".equals(parts[0])) return false;
      int n = Integer.parseInt(parts[1]);
      int r = Integer.parseInt(parts[2]);
      int p = Integer.parseInt(parts[3]);
      byte[] salt = HexFormatSupport.decode(parts[4]);
      byte[] expected = HexFormatSupport.decode(parts[5]);
      byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, n, r, p, expected.length);
      return MessageDigest.isEqual(actual, expected);
    } catch (Exception e) {
      return false;
    }
  }

  private static final class HexFormatSupport {
    static byte[] decode(String hex) {
      if ((hex.length() & 1) != 0) throw new IllegalArgumentException("bad hex");
      byte[] out = new byte[hex.length() / 2];
      for (int i = 0; i < out.length; i++) {
        out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
      return out;
    }
  }
}
