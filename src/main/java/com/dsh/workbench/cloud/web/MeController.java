package com.dsh.workbench.cloud.web;

import com.dsh.workbench.cloud.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MeController {
  private final AuthService auth;

  public MeController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/v1/me")
  public Map<String, Object> me(HttpServletRequest request) {
    return auth.me(principal(request));
  }

  @DeleteMapping("/v1/devices/{id}")
  public Map<String, Object> revoke(@PathVariable String id, HttpServletRequest request) {
    AuthPrincipal principal = principal(request);
    auth.revokeDevice(principal.userId(), id);
    return Map.of("ok", true);
  }

  static AuthPrincipal principal(HttpServletRequest request) {
    return (AuthPrincipal) request.getAttribute(AuthFilter.ATTR);
  }
}
