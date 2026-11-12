package com.klob.echo;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SyncDeviceTest {
  @Test
  public void tlsSyncRequiresDurableAcknowledgementAndKeepsOriginal() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    HeldCertificate certificate =
        new HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build();
    HandshakeCertificates serverCertificates =
        new HandshakeCertificates.Builder().heldCertificate(certificate).build();
    HandshakeCertificates clientCertificates =
        new HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate())
            .build();
    javax.net.ssl.SSLSocketFactory previous = HttpsURLConnection.getDefaultSSLSocketFactory();
    try (MockWebServer server = new MockWebServer()) {
      server.useHttps(serverCertificates.sslSocketFactory(), false);
      server.start();
      HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory());
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              return new MockResponse().setResponseCode(200).setBody("{\"durable\":false}");
            }
          });
      String fixture = "test-only-not-a-real-token-00000000000000000000";
      SyncCredentials.set(context, fixture);
      context
          .getSharedPreferences(SaidItService.PREFS, 0)
          .edit()
          .putString("sync_url", server.url("/").toString())
          .commit();
      context.getSharedPreferences("sync_ack", 0).edit().clear().commit();
      File file =
          new TraceStore(context).text("_sync_fixture", ".txt", "Generated HTTPS test fixture");
      CountDownLatch failed = new CountDownLatch(1);
      TraceSync sync =
          new TraceSync(
              context,
              message -> {
                if (message.startsWith("Sync incomplete")) failed.countDown();
              });
      sync.sync();
      assertTrue(failed.await(15, TimeUnit.SECONDS));
      sync.close();
      assertTrue(file.exists());
      assertTrue(context.getSharedPreferences("sync_ack", 0).getAll().isEmpty());
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              String hash = request.getPath().substring(request.getPath().lastIndexOf('/') + 1);
              return new MockResponse()
                  .setResponseCode(200)
                  .setHeader("Content-Type", "application/json")
                  .setBody(
                      "{\"durable\":true,\"sha256\":\""
                          + hash
                          + "\",\"bytes\":"
                          + request.getBodySize()
                          + "}");
            }
          });
      CountDownLatch done = new CountDownLatch(1);
      TraceSync retry =
          new TraceSync(
              context,
              message -> {
                if (message.startsWith("Sync complete")) done.countDown();
              });
      retry.sync();
      assertTrue(done.await(30, TimeUnit.SECONDS));
      retry.close();
      assertTrue(file.exists());
      assertFalse(context.getSharedPreferences("sync_ack", 0).getAll().isEmpty());
      RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
      assertEquals("Bearer " + fixture, request.getHeader("Authorization"));
      assertFalse(request.getPath().contains("token"));
    } finally {
      HttpsURLConnection.setDefaultSSLSocketFactory(previous);
      context.getSharedPreferences("sync_secret", 0).edit().clear().commit();
      context.getSharedPreferences("sync_ack", 0).edit().clear().commit();
      context
          .getSharedPreferences(SaidItService.PREFS, 0)
          .edit()
          .remove("sync_url")
          .remove("sync")
          .commit();
    }
  }
}
