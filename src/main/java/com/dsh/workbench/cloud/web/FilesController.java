package com.dsh.workbench.cloud.web;

import com.dsh.workbench.cloud.service.FileService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
public class FilesController {
  private static final Pattern SHA_RE = Pattern.compile("^[0-9a-f]{64}$");
  private final FileService files;

  public FilesController(FileService files) {
    this.files = files;
  }

  @PutMapping("/v1/files/blobs/{sha256}")
  public ResponseEntity<Map<String, Object>> put(@PathVariable String sha256,
                                                 HttpServletRequest request,
                                                 @RequestHeader(value = "Content-Type", required = false) String contentType) throws IOException {
    if (!SHA_RE.matcher(sha256).matches()) {
      throw new ApiException(400, "invalid_sha256", "sha256 must be lowercase hex");
    }
    long length = request.getContentLengthLong();
    if (length < 0) throw new ApiException(411, "length_required", "Content-Length required");
    AuthPrincipal p = MeController.principal(request);
    FileService.PutResult result = files.put(p.userId(), sha256, request.getInputStream(), length, contentType);
    return ResponseEntity.status(result.deduped() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(Map.of("ok", true, "sha256", sha256, "size", result.size(), "deduped", result.deduped()));
  }

  @PostMapping("/v1/files/check")
  public Map<String, Object> check(@RequestBody JsonNode body, HttpServletRequest request) {
    JsonNode hashesNode = body.get("hashes");
    if (hashesNode == null || !hashesNode.isArray() || hashesNode.size() > 200) {
      throw new ApiException(400, "invalid_hashes", "hashes must be an array of at most 200");
    }
    List<String> hashes = new ArrayList<>();
    for (JsonNode node : hashesNode) {
      String sha = node.asText();
      if (!SHA_RE.matcher(sha).matches()) throw new ApiException(400, "invalid_sha256", "invalid sha256");
      hashes.add(sha);
    }
    AuthPrincipal p = MeController.principal(request);
    return files.check(p.userId(), hashes);
  }

  @GetMapping("/v1/files/quota")
  public Map<String, Object> quota(HttpServletRequest request) {
    return files.quota(MeController.principal(request).userId());
  }

  @GetMapping("/v1/files/blobs/{sha256}")
  public ResponseEntity<?> get(@PathVariable String sha256,
                               @RequestHeader(value = "Range", required = false) String rangeHeader,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
    if (!SHA_RE.matcher(sha256).matches()) throw new ApiException(400, "invalid_sha256", "invalid sha256");
    AuthPrincipal p = MeController.principal(request);
    FileService.BlobRecord blob = files.requireBlob(p.userId(), sha256);
    if (!files.isReferenced(p.userId(), sha256)) {
      throw new ApiException(404, "blob_not_referenced", "blob is not referenced by a live file");
    }
    String proxy = request.getParameter("proxy");
    if (!"1".equals(proxy) && "s3".equals(blob.backend())) {
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, URI.create(files.presign(blob)).toString())
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .build();
    }
    files.touch(p.userId(), sha256);
    Long start = null;
    Long end = null;
    boolean partial = false;
    if (rangeHeader != null && !rangeHeader.isBlank()) {
      List<HttpRange> ranges;
      try {
        ranges = HttpRange.parseRanges(rangeHeader);
      } catch (IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
      }
      if (!ranges.isEmpty()) {
        HttpRange range = ranges.get(0);
        start = range.getRangeStart(blob.size());
        end = range.getRangeEnd(blob.size());
        if (start < 0 || start >= blob.size() || end < start) {
          return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }
        partial = true;
      }
    }
    var stream = files.open(blob, start, end);
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.CONTENT_TYPE, blob.mime() == null ? "application/octet-stream" : blob.mime());
    headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(stream.size()));
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.set("X-Content-Type-Options", "nosniff");
    if (partial) {
      headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + blob.size());
    }
    StreamingResponseBody body = out -> {
      try (InputStream in = stream.stream()) {
        in.transferTo(out);
      }
    };
    return ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
        .headers(headers).body(body);
  }

  @RequestMapping(value = "/v1/files/blobs/{sha256}", method = RequestMethod.HEAD)
  public ResponseEntity<Void> head(@PathVariable String sha256, HttpServletRequest request) {
    if (!SHA_RE.matcher(sha256).matches()) throw new ApiException(400, "invalid_sha256", "invalid sha256");
    AuthPrincipal p = MeController.principal(request);
    FileService.BlobRecord blob = files.requireBlob(p.userId(), sha256);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentLength(blob.size());
    headers.set(HttpHeaders.CONTENT_TYPE, blob.mime() == null ? "application/octet-stream" : blob.mime());
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.set("X-Content-Type-Options", "nosniff");
    return ResponseEntity.ok().headers(headers).build();
  }
}
