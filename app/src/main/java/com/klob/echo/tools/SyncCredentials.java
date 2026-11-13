package com.klob.echo.tools;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import com.klob.echo.BuildConfig;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Native Android Keystore protects the optional sync token; plaintext never enters logs. */
public final class SyncCredentials {
  private static final String ALIAS = BuildConfig.APPLICATION_ID + ".sync";

  private static javax.crypto.SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (javax.crypto.SecretKey) store.getKey(ALIAS, null);
  }

  public static void set(Context context, String token) throws Exception {
    if (token.length() < 32
        || token.length() > 4096
        || token.contains("\r")
        || token.contains("\n")) throw new IllegalArgumentException("Token length");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String encrypted =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(
                cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    context.getSharedPreferences("sync_secret", 0).edit().putString("encrypted", encrypted).apply();
  }

  public static String get(Context context) throws Exception {
    String encrypted = context.getSharedPreferences("sync_secret", 0).getString("encrypted", "");
    if (encrypted.isEmpty()) return "";
    String[] parts = encrypted.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
        java.nio.charset.StandardCharsets.UTF_8);
  }
}
