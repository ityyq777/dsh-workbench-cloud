package com.dsh.workbench.cloud.web;

import com.dsh.workbench.cloud.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
  public static final String ATTR = "wb.auth";

  private final AuthService auth;
  private final ObjectMapper mapper;

  public AuthFilter(AuthService auth, ObjectMapper mapper) {
    this.auth = auth;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/v1/auth/")
        || path.equals("/v1/health")
        || path.startsWith("/actuator/")
        || path.equals("/error");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    AuthPrincipal principal = auth.authenticate(request.getHeader("Authorization"));
    if (principal == null) {
      response.setStatus(401);
      response.setContentType("application/json; charset=utf-8");
      mapper.writeValue(response.getOutputStream(), Map.of("ok", false, "error", "unauthorized"));
      return;
    }
    request.setAttribute(ATTR, principal);
    chain.doFilter(request, response);
  }
}
