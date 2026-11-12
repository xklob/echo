package com.klob.echo;

/** Grants access to this test APK's generated documents from their owning UID. */
public final class GrantTestDocumentsActivity extends android.app.Activity {
  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    for (String mode :
        new String[] {"revoked", "write-failure", "truncated", "cancel", "success", "root"}) {
      android.net.Uri tree =
          android.net.Uri.parse("content://com.klob.echo.test.documents/tree/" + mode);
      int flags =
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
              | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
      if (getIntent().getBooleanExtra("revoke", false)) revokeUriPermission(tree, flags);
      else
        grantUriPermission(
            "com.klob.echo", tree, flags | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }
    finish();
  }
}
