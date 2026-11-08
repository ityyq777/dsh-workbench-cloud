package com.dsh.workbench.cloud.util;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Stable JSON serialization: object keys sorted recursively. */
public final class CanonicalJson {
  private CanonicalJson() {}

  public static String stringify(JsonNode value) {
    if (value == null || value.isNull()) return "null";
    if (value.isTextual()) return quote(value.textValue());
    if (value.isBoolean()) return value.booleanValue() ? "true" : "false";
    if (value.isNumber()) {
      if (value.isIntegralNumber()) return value.bigIntegerValue().toString();
      return value.decimalValue().stripTrailingZeros().toPlainString();
    }
    if (value.isArray()) {
      List<String> parts = new ArrayList<>();
      for (JsonNode item : value) parts.add(stringify(item));
      return "[" + String.join(",", parts) + "]";
    }
    if (value.isObject()) {
      List<String> keys = new ArrayList<>();
      Iterator<String> it = value.fieldNames();
      while (it.hasNext()) keys.add(it.next());
      Collections.sort(keys);
      List<String> parts = new ArrayList<>();
      for (String key : keys) parts.add(quote(key) + ":" + stringify(value.get(key)));
      return "{" + String.join(",", parts) + "}";
    }
    return "null";
  }

  public static String quote(String text) {
    return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(text)) + "\"";
  }
}
