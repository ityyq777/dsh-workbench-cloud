package com.dsh.workbench.cloud.web;

import com.dsh.workbench.cloud.service.AuthService;
import com.dsh.workbench.cloud.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/v1/auth/signup")
  public Map<String, Object> signup(@RequestBody JsonNode body) {
    var session = auth.signup(Json.text(body, "email"), Json.text(body, "password"),
        Json.text(body, "device_id"), Json.text(body, "device_name"));
    return session(session);
  }

  @PostMapping("/v1/auth/login")
  public Map<String, Object> login(@RequestBody JsonNode body) {
    var session = auth.login(Json.text(body, "email"), Json.text(body, "password"),
        Json.text(body, "device_id"), Json.text(body, "device_name"));
    return session(session);
  }

  @PostMapping("/v1/auth/refresh")
  public Map<String, Object> refresh(@RequestBody JsonNode body) {
    var session = auth.refresh(Json.text(body, "refresh_token"));
    if (session == null) throw new ApiException(401, "invalid_refresh", "invalid or reused refresh");
    return session(session);
  }

  private static Map<String, Object> session(AuthService.Session s) {
    return Json.map("ok", true, "accessToken", s.accessToken(), "refreshToken", s.refreshToken(),
        "userId", s.userId(), "deviceId", s.deviceId());
  }
}
