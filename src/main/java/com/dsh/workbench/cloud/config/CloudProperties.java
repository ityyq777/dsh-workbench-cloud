package com.dsh.workbench.cloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wb")
public class CloudProperties {
  private final Auth auth = new Auth();
  private final Sync sync = new Sync();
  private final Files files = new Files();
  private final Blob blob = new Blob();

  public Auth getAuth() { return auth; }
  public Sync getSync() { return sync; }
  public Files getFiles() { return files; }
  public Blob getBlob() { return blob; }

  public static class Auth {
    private long accessTtlSeconds = 900;
    private long refreshTtlSeconds = 7_776_000;
    public long getAccessTtlSeconds() { return accessTtlSeconds; }
    public void setAccessTtlSeconds(long v) { this.accessTtlSeconds = v; }
    public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
    public void setRefreshTtlSeconds(long v) { this.refreshTtlSeconds = v; }
  }

  public static class Sync {
    private long sessionTtlSeconds = 300;
    public long getSessionTtlSeconds() { return sessionTtlSeconds; }
    public void setSessionTtlSeconds(long v) { this.sessionTtlSeconds = v; }
  }

  public static class Files {
    private long maxBytes = 200L * 1024 * 1024;
    private long quotaBytes = 20L * 1024 * 1024 * 1024;
    private boolean gcEnabled = true;
    private long gcGraceDays = 7;
    private long gcIntervalMs = 21_600_000;
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long v) { this.maxBytes = v; }
    public long getQuotaBytes() { return quotaBytes; }
    public void setQuotaBytes(long v) { this.quotaBytes = v; }
    public boolean isGcEnabled() { return gcEnabled; }
    public void setGcEnabled(boolean v) { this.gcEnabled = v; }
    public long getGcGraceDays() { return gcGraceDays; }
    public void setGcGraceDays(long v) { this.gcGraceDays = v; }
    public long getGcIntervalMs() { return gcIntervalMs; }
    public void setGcIntervalMs(long v) { this.gcIntervalMs = v; }
  }

  public static class Blob {
    private String backend = "s3";
    private String dir = "./data/blobs";
    private final S3 s3 = new S3();
    public String getBackend() { return backend; }
    public void setBackend(String v) { this.backend = v; }
    public String getDir() { return dir; }
    public void setDir(String v) { this.dir = v; }
    public S3 getS3() { return s3; }

    public static class S3 {
      private String endpoint = "";
      private String region = "us-east-1";
      private String bucket = "workbench";
      private String prefix = "";
      private String accessKeyId = "";
      private String secretAccessKey = "";
      private boolean forcePathStyle = true;
      private String sse = "";
      private boolean allowInsecure = false;
      public String getEndpoint() { return endpoint; }
      public void setEndpoint(String v) { this.endpoint = v; }
      public String getRegion() { return region; }
      public void setRegion(String v) { this.region = v; }
      public String getBucket() { return bucket; }
      public void setBucket(String v) { this.bucket = v; }
      public String getPrefix() { return prefix; }
      public void setPrefix(String v) { this.prefix = v; }
      public String getAccessKeyId() { return accessKeyId; }
      public void setAccessKeyId(String v) { this.accessKeyId = v; }
      public String getSecretAccessKey() { return secretAccessKey; }
      public void setSecretAccessKey(String v) { this.secretAccessKey = v; }
      public boolean isForcePathStyle() { return forcePathStyle; }
      public void setForcePathStyle(boolean v) { this.forcePathStyle = v; }
      public String getSse() { return sse; }
      public void setSse(String v) { this.sse = v; }
      public boolean isAllowInsecure() { return allowInsecure; }
      public void setAllowInsecure(boolean v) { this.allowInsecure = v; }
    }
  }
}
