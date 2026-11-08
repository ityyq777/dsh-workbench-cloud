package com.dsh.workbench.cloud.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Json {
  private Json() {}

  public static String text(JsonNode node, String field) {
    if (node == null || !node.has(field) || node.get(field).isNull()) return null;
    String value = node.get(field).asText();
    return value.isEmpty() ? null : value;
  }

  public static String textOr(JsonNode node, String field, String fallback) {
    String value = text(node, field);
    return value == null ? fallback : value;
  }

  public static Long longValue(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isIntegralNumber()) return node.longValue();
    if (node.isTextual()) {
      try {
        return Long.parseLong(node.textValue().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  public static Long longField(JsonNode node, String field) {
    return node == null ? null : longValue(node.get(field));
  }

  public static Map<String, Object> map(Object... kv) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
    return out;
  }
}
