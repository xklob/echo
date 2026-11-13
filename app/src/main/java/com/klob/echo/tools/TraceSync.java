package com.klob.echo.tools;

import android.content.*;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.storage.ClipStore;
import com.klob.echo.storage.Io;
import com.klob.echo.storage.TraceStore;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Retains originals, verifies durable hash/length acknowledgements, and retries idempotently. */
public final class TraceSync {
  private final Context context;
  private final java.util.function.Consumer<String> status;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile boolean closed;
  private volatile HttpURLConnection active;

  public TraceSync(Context context, java.util.function.Consumer<String> status) {
    this.context = context.getApplicationContext();
    this.status = status;
  }

  public static String endpoint(String value) throws Exception {
    URI uri = new URI(value.trim());
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException(
          "Use an HTTPS base URL without credentials, query or fragment");
    return uri.toString().replaceAll("/+$", "");
  }

  public void sync() {
    if (closed || !running.compareAndSet(false, true)) return;
    worker.execute(
        () -> {
          try {
            run();
          } catch (Exception e) {
            status.accept(
                "Sync incomplete; local originals kept. Check destination, credentials and"
                    + " network.");
          } finally {
            running.set(false);
          }
        });
  }

  private void run() throws Exception {
    SharedPreferences prefs = context.getSharedPreferences(RecordingService.PREFS, 0),
        ack = context.getSharedPreferences("sync_ack", 0);
    String base = endpoint(prefs.getString("sync_url", "")), token = SyncCredentials.get(context);
    if (token.isEmpty()) throw new IOException("No token");
    String destination =
        hash(base.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
    List<File> files = new ClipStore(context).list();
    files.addAll(new TraceStore(context).list());
    for (File file : new ArrayList<>(files)) {
      File metadata = new File(file.getPath() + ".json");
      if (metadata.isFile()) files.add(metadata);
    }
    files.sort(Comparator.comparingLong(File::lastModified));
    int uploaded = 0, oversized = 0;
    for (File file : files) {
      if (closed) throw new IOException("Stopped");
      if (!file.isFile()) continue;
      if (file.length() > 128L * 1048576) {
        oversized++;
        continue;
      }
      String digest = hash(file),
          identity =
              destination
                  + "_"
                  + digest
                  + "_"
                  + hash(file.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                      .substring(0, 12);
      if (ack.contains(identity)) continue;
      try (InputStream in = new FileInputStream(file)) {
        put(base, token, digest, file.length(), in);
      }
      JSONObject manifest = new JSONObject();
      manifest.put("type", "echo_manifest_v1");
      manifest.put("name", file.getName());
      manifest.put("sha256", digest);
      manifest.put("bytes", file.length());
      manifest.put("modified_ms", file.lastModified());
      manifest.put("mime", ClipStore.mime(file));
      byte[] bytes = manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      put(base, token, hash(bytes), bytes.length, new ByteArrayInputStream(bytes));
      ack.edit().putLong(identity, System.currentTimeMillis()).apply();
      uploaded++;
      Map<String, ?> entries = ack.getAll();
      if (entries.size() > 1000) {
        List<String> keys = new ArrayList<>(entries.keySet());
        keys.sort(Comparator.comparingLong(k -> ((Number) entries.get(k)).longValue()));
        SharedPreferences.Editor edit = ack.edit();
        for (int i = 0; i < entries.size() - 1000; i++) edit.remove(keys.get(i));
        edit.apply();
      }
    }
    status.accept(
        "Sync complete // "
            + uploaded
            + " items. "
            + (oversized > 0 ? oversized + " over 128 MiB skipped. " : "")
            + "Local originals kept.");
  }

  private void put(String base, String token, String hash, long length, InputStream input)
      throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(base + "/v1/traces/" + hash).openConnection();
    active = connection;
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setRequestMethod("PUT");
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("Content-Type", "application/octet-stream");
      connection.setFixedLengthStreamingMode(length);
      connection.setDoOutput(true);
      try (OutputStream out = connection.getOutputStream()) {
        byte[] buffer = new byte[32768];
        int n;
        while ((n = input.read(buffer)) != -1) {
          if (closed) throw new IOException("Stopped");
          out.write(buffer, 0, n);
        }
      }
      if (connection.getResponseCode() != 200)
        throw new IOException("Sync acknowledgement missing");
      try (InputStream response = connection.getInputStream()) {
        JSONObject json =
            new JSONObject(
                new String(Io.readBytes(response, 4096), java.nio.charset.StandardCharsets.UTF_8));
        if (!json.optBoolean("durable")
            || !hash.equals(json.optString("sha256"))
            || length != json.optLong("bytes", -1))
          throw new IOException("Invalid durable acknowledgement");
      }
    } finally {
      connection.disconnect();
      active = null;
    }
  }

  private static String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[32768];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    return hex(digest.digest());
  }

  private static String hash(byte[] bytes) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String hex(byte[] bytes) {
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  public void close() {
    closed = true;
    HttpURLConnection connection = active;
    if (connection != null) connection.disconnect();
    worker.shutdownNow();
  }
}
