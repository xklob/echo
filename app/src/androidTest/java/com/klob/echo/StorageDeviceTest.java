package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.klob.echo.storage.ClipStore;
import com.klob.echo.storage.Io;
import java.io.*;
import java.util.concurrent.CancellationException;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StorageDeviceTest {
  @Test
  public void providerFailuresAndCancellationNeverPublishBadCopy() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ClipStore store = new ClipStore(context);
    context.startActivity(
        new Intent()
            .setClassName("com.klob.echo.test", "com.klob.echo.GrantTestDocumentsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    for (int i = 0;
        i < 100
            && context.checkUriPermission(
                    Uri.parse("content://com.klob.echo.test.documents/tree/success"),
                    android.os.Process.myPid(),
                    android.os.Process.myUid(),
                    flags)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
        i++) Thread.sleep(20);
    File source = store.temporary(0, "manual", "wav");
    Io.write(source.toPath(), "Generated storage fixture");
    File complete = store.complete(source);
    try {
      for (String failure : new String[] {"revoked", "write-failure", "truncated"}) {
        String tree = "content://com.klob.echo.test.documents/tree/" + failure;
        try {
          store.copyToTree(complete, tree, () -> false);
          fail("Failure was reported as saved: " + failure);
        } catch (IOException expected) {
        }
        assertTrue(complete.exists());
      }
      try {
        store.copyToTree(
            complete, "content://com.klob.echo.test.documents/tree/cancel", () -> true);
        fail();
      } catch (CancellationException expected) {
      }
      try (android.database.Cursor c =
          context
              .getContentResolver()
              .query(
                  DocumentsContract.buildChildDocumentsUriUsingTree(
                      Uri.parse("content://com.klob.echo.test.documents/tree/root"), "root"),
                  null,
                  null,
                  null,
                  null)) {
        assertNotNull(c);
        assertEquals(0, c.getCount());
      }
      Uri copied =
          store.copyToTree(
              complete, "content://com.klob.echo.test.documents/tree/success", () -> false);
      try (InputStream in = context.getContentResolver().openInputStream(copied)) {
        assertArrayEquals(
            "Generated storage fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            Io.readBytes(in, 100));
      }
      DocumentsContract.deleteDocument(context.getContentResolver(), copied);
      context.startActivity(
          new Intent()
              .setClassName("com.klob.echo.test", "com.klob.echo.GrantTestDocumentsActivity")
              .putExtra("revoke", true)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      for (int i = 0;
          i < 100
              && context.checkUriPermission(
                      Uri.parse("content://com.klob.echo.test.documents/tree/success"),
                      android.os.Process.myPid(),
                      android.os.Process.myUid(),
                      flags)
                  == android.content.pm.PackageManager.PERMISSION_GRANTED;
          i++) Thread.sleep(20);
      assertEquals(
          android.content.pm.PackageManager.PERMISSION_DENIED,
          context.checkUriPermission(
              Uri.parse("content://com.klob.echo.test.documents/tree/success"),
              android.os.Process.myPid(),
              android.os.Process.myUid(),
              flags));
      try {
        store.copyToTree(
            complete, "content://com.klob.echo.test.documents/tree/success", () -> false);
        fail("Revoked URI permission accepted");
      } catch (IOException expected) {
      }
      assertTrue(complete.exists());

    } finally {
      complete.delete();
    }
  }

  @Test
  public void retentionOnlyRotatesAutomaticCategory() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ClipStore store = new ClipStore(context);
    File manual = store.complete(store.temporary(0, "manual", "wav"));
    File auto1 = store.complete(store.temporary(1, "auto", "wav"));
    File auto2 = store.complete(store.temporary(2, "auto", "wav"));
    auto1.setLastModified(1);
    auto2.setLastModified(2);
    try {
      store.rotate("auto", 1);
      assertTrue(manual.exists());
      assertFalse(auto1.exists());
      store.rotate("manual", 1);
      assertTrue(manual.exists());
    } finally {
      manual.delete();
      auto1.delete();
      auto2.delete();
    }
  }
}
