package com.klob.echo;

import android.content.*;
import android.media.*;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Single in-app playback owner. No second player or capture stream. */
public final class RecordingsActivity extends AppCompatActivity {
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private ClipStore store;
    private LinearLayout list;
    private TextView status;
    private MediaPlayer player;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store = new ClipStore(this); LinearLayout body = Ui.screen(this);
        Ui.text(this, body, "Recordings", 28); status = Ui.text(this, body, "", 14);
        Ui.button(this, body, "STOP PLAYBACK", this::stopPlayer); Ui.button(this, body, "BACK", this::finish);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); body.addView(list); refresh();
    }
    private void refresh() { files.execute(() -> { List<File> recordings = store.list(); runOnUiThread(() -> render(recordings)); }); }
    private void render(List<File> recordings) {
        if (isFinishing() || isDestroyed()) return; list.removeAllViews();
        status.setText(recordings.isEmpty() ? "No saved recordings." : "Latest first // " + recordings.size() + " recordings");
        for (File file : recordings) {
            Ui.text(this, list, file.getName() + "\n" + file.getParentFile().getName() + " // " + file.length() / 1024 + " KB", 14);
            Ui.button(this, list, "PLAY", () -> play(file)); Ui.button(this, list, "SHARE", () -> share(file));
            Ui.button(this, list, "RENAME", () -> rename(file));
            Ui.button(this, list, "DELETE", () -> new androidx.appcompat.app.AlertDialog.Builder(this).setMessage("Delete " + file.getName() + "?").setNegativeButton("CANCEL", null).setPositiveButton("DELETE", (d,w) -> {
                stopPlayer(); files.execute(() -> { boolean ok = file.delete(); runOnUiThread(() -> { if (!ok) status.setText("Could not delete recording."); refresh(); }); });
            }).show());
        }
    }
    private void play(File file) {
        stopPlayer();
        try {
            player = new MediaPlayer(); player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(file.getPath()); player.setOnPreparedListener(p -> { p.start(); status.setText("Playing // " + file.getName()); });
            player.setOnCompletionListener(p -> stopPlayer()); player.setOnErrorListener((p,w,e) -> { stopPlayer(); status.setText("Playback failed."); return true; }); player.prepareAsync();
        } catch (IOException | RuntimeException e) { stopPlayer(); status.setText("Could not play this recording."); }
    }
    private void share(File file) {
        try {
            android.net.Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file);
            Intent share = new Intent(Intent.ACTION_SEND).setType(ClipStore.mime(file)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("Recording", uri));
            startActivity(Intent.createChooser(share, "Share recording"));
        } catch (RuntimeException e) { status.setText("No app could share this recording."); }
    }
    private void rename(File file) {
        EditText name = new EditText(this); String base = file.getName().substring(0, file.getName().lastIndexOf('.')); name.setText(base); name.setSelectAllOnFocus(true);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Rename").setView(name).setNegativeButton("CANCEL", null).setPositiveButton("RENAME", (d,w) -> {
            String next = name.getText().toString().trim();
            if (next.isEmpty() || next.length() > 120 || next.contains("/") || next.contains("\\") || next.equals(".") || next.equals("..")) { status.setText("Choose a shorter name without path separators."); return; }
            File target = new File(file.getParentFile(), next + file.getName().substring(file.getName().lastIndexOf('.'))); stopPlayer();
            files.execute(() -> { try { Files.move(file.toPath(), target.toPath()); } catch (IOException e) { runOnUiThread(() -> status.setText("Rename failed; that name may already exist.")); } refresh(); });
        }).show();
    }
    private void stopPlayer() { if (player != null) { player.release(); player = null; } }
    @Override protected void onStop() { stopPlayer(); super.onStop(); }
    @Override protected void onDestroy() { files.shutdown(); super.onDestroy(); }
}
