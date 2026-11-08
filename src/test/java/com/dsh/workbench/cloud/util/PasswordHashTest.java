package com.dsh.workbench.cloud.util;

import com.dsh.workbench.cloud.service.AuthService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHashTest {
  @Test
  void roundTrip() {
    String stored = AuthService.hashPassword("correct horse battery staple");
    assertTrue(stored.startsWith("scrypt_"));
    assertTrue(AuthService.verifyPassword("correct horse battery staple", stored));
    assertFalse(AuthService.verifyPassword("wrong", stored));
  }

  @Test
  void rejectsGarbage() {
    assertFalse(AuthService.verifyPassword("x", "not-a-hash"));
    assertFalse(AuthService.verifyPassword("x", "scrypt_1_2_3_zz_zz"));
  }
}
