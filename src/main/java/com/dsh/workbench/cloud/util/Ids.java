package com.dsh.workbench.cloud.util;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Ids() {}

  /** UUIDv7: 48-bit unix millis, version 7, RFC 4122 variant. */
  public static String uuidv7() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    long ts = System.currentTimeMillis();
    bytes[0] = (byte) (ts >>> 40);
    bytes[1] = (byte) (ts >>> 32);
    bytes[2] = (byte) (ts >>> 24);
    bytes[3] = (byte) (ts >>> 16);
    bytes[4] = (byte) (ts >>> 8);
    bytes[5] = (byte) ts;
    bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
    bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) msb = (msb << 8) | (bytes[i] & 0xffL);
    for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (bytes[i] & 0xffL);
    return new UUID(msb, lsb).toString();
  }
}
