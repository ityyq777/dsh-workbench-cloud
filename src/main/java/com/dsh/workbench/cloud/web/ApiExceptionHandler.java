package com.dsh.workbench.cloud.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> api(ApiException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    body.put("code", e.code());
    body.put("error", e.getMessage());
    body.putAll(e.extra());
    return ResponseEntity.status(e.status()).body(body);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(Map.of("ok", false, "code", "invalid_json", "error", "invalid JSON body"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    log.error("unhandled error", e);
    return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", "internal error"));
  }
}
