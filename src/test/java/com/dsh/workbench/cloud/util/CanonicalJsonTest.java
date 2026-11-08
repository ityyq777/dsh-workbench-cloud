package com.dsh.workbench.cloud.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalJsonTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void sortsKeysRecursively() throws Exception {
    var node = mapper.readTree("{\"b\":1,\"a\":{\"d\":true,\"c\":[2,{\"z\":\"x\",\"y\":null}]}}");
    assertEquals("{\"a\":{\"c\":[2,{\"y\":null,\"z\":\"x\"}],\"d\":true},\"b\":1}",
        CanonicalJson.stringify(node));
  }

  @Test
  void escapesText() throws Exception {
    var node = mapper.readTree("{\"s\":\"a\\\"b\\n\"}");
    assertEquals("{\"s\":\"a\\\"b\\n\"}", CanonicalJson.stringify(node));
  }
}
