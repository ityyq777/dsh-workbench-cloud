package com.dsh.workbench.cloud.web;

import com.dsh.workbench.cloud.service.SyncService;
import com.dsh.workbench.cloud.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

@RestController
public class SyncController {
  private final SyncService sync;

  public SyncController(SyncService sync) {
    this.sync = sync;
  }

  @PostMapping("/v1/sync/open")
  public Map<String, Object> open(@RequestBody JsonNode body, HttpServletRequest request) {
    AuthPrincipal p = MeController.principal(request);
    String deviceId = Objects.requireNonNullElse(Json.text(body, "device_id"), p.deviceId());
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("ok", true);
    out.putAll(sync.open(p.userId(), deviceId, body.get("protocol")));
    return out;
  }

  @PostMapping("/v1/sync/push")
  public Map<String, Object> push(@RequestBody JsonNode body, HttpServletRequest request) {
    AuthPrincipal p = MeController.principal(request);
    return sync.push(p.userId(), p.deviceId(), Json.text(body, "client_epoch"),
        Json.text(body, "session_id"), body.get("changes"));
  }

  @PostMapping("/v1/sync/pull")
  public Map<String, Object> pull(@RequestBody JsonNode body, HttpServletRequest request) {
    AuthPrincipal p = MeController.principal(request);
    return sync.pull(p.userId(), p.deviceId(), Json.text(body, "session_id"),
        body.get("cursors"), body.get("limit"));
  }

  @PostMapping("/v1/sync/snapshot")
  public Map<String, Object> snapshot(@RequestBody JsonNode body, HttpServletRequest request) {
    AuthPrincipal p = MeController.principal(request);
    return sync.snapshot(p.userId(), p.deviceId(), Json.text(body, "session_id"), Json.text(body, "table"));
  }

  @PostMapping("/v1/sync/full")
  public Map<String, Object> full(@RequestBody JsonNode body, HttpServletRequest request) {
    AuthPrincipal p = MeController.principal(request);
    return sync.full(p.userId(), p.deviceId(), Json.text(body, "session_id"), Json.text(body, "table"),
        body.get("base_version"), body.get("rows"));
  }

  @PostMapping("/v1/sync/close")
  public Map<String, Object> close(@RequestBody JsonNode body, HttpServletRequest request) {
    AuthPrincipal p = MeController.principal(request);
    return sync.close(p.userId(), p.deviceId(), Json.text(body, "session_id"),
        body.path("success").asBoolean(false), body.get("cursors"));
  }
}
