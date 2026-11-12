package com.klob.echo;

import android.app.*;
import android.content.*;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.klob.echo.core.ListeningRules;
import java.util.*;

public final class SettingsActivity extends AppCompatActivity {
    private android.content.SharedPreferences prefs;
    private EditText ram, disk, interval, keep, from, to, battery;
    private SwitchMaterial diskOn, schedule, autosave, fill, automation;
    private Spinner format, quality;
    private TextView location, status;
    private final List<String> formats = new ArrayList<>(Arrays.asList("opus", "wav"));
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs = getSharedPreferences(SaidItService.PREFS, MODE_PRIVATE);
        LinearLayout body = Ui.screen(this); Ui.text(this, body, "Settings", 28);
        status = Ui.text(this, body, "Stop listening before changing history or quality. Changing these starts a new history.", 14);
        Ui.text(this, body, "History", 22);
        ram = number(body, "RAM history budget in MB // max " + SaidItService.maxRamMb(), prefs.getInt("ram_mb", 16));
        Ui.text(this, body, "Saving can retain up to one additional history budget. Disk mode reserves half its limit for a save in progress.", 14);
        diskOn = toggle(body, "Use disk history", prefs.getBoolean("disk", false));
        disk = number(body, "Total disk limit in MB // 16–1024", prefs.getInt("disk_mb", 128));
        Ui.text(this, body, "Opus history bitrate", 16);
        quality = spinner(body, Arrays.asList("32 kb/s", "64 kb/s", "96 kb/s")); quality.setSelection(prefs.getInt("bitrate", 32000) == 96000 ? 2 : prefs.getInt("bitrate", 32000) == 64000 ? 1 : 0);
        if (PlatformEncoder.supported(MediaFormat.MIMETYPE_AUDIO_FLAC)) formats.add("flac");
        if (PlatformEncoder.supported(MediaFormat.MIMETYPE_AUDIO_AAC)) formats.add("m4a");
        Ui.text(this, body, "Export format // Opus is stored in Ogg", 16); format = spinner(body, formats); format.setSelection(Math.max(0, formats.indexOf(prefs.getString("format", "opus"))));
        Ui.text(this, body, "WAV and FLAC decode the compressed history. They cannot restore quality lost during Opus capture.", 14);
        Ui.text(this, body, "Automatic saves", 22);
        autosave = toggle(body, "Autosave", prefs.getBoolean("autosave", false)); fill = toggle(body, "Also save when history is 75% full", prefs.getBoolean("autosave_fill", false));
        interval = number(body, "Save interval / continuous segment seconds // 10–3600", prefs.getInt("autosave_seconds", 300));
        keep = number(body, "Keep automatic clips per category // 1–500", prefs.getInt("auto_keep", 48));
        Ui.text(this, body, "Only automatic, continuous and activity clips rotate. Manual clips stay until you delete them.", 14);
        Ui.text(this, body, "Listening rules", 22);
        schedule = toggle(body, "Active hours", prefs.getBoolean("schedule", false));
        from = time(body, "Start // HH:mm", prefs.getInt("schedule_from", 0)); to = time(body, "End // HH:mm", prefs.getInt("schedule_to", 0));
        battery = number(body, "Minimum battery % // 0 disables", prefs.getInt("battery_min", 0));
        Ui.text(this, body, "Overnight hours are supported. Equal times mean all day. Android may delay transitions when exact-alarm access is denied. After reboot, tap Echo to resume. STOP always stays stopped.", 14);
        Ui.text(this, body, "Storage", 22); location = Ui.text(this, body, "", 14); updateLocation();
        Ui.button(this, body, "CHOOSE COPY FOLDER", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 20));
        Ui.button(this, body, "USE LOCAL STORAGE ONLY", () -> { releaseTree(); prefs.edit().remove("tree").apply(); updateLocation(); });
        automation = toggle(body, "Allow external automation", prefs.getBoolean("automation", false));
        Ui.text(this, body, "External intents can save or stop recording. Android may require opening Echo before starting the microphone.", 14);
        Ui.button(this, body, "APPLY SETTINGS", this::apply);
        Ui.button(this, body, "BACK", this::finish);
    }
    private void apply() {
        try {
            int memory = range(ram, 1, SaidItService.maxRamMb()), diskMb = range(disk, 16, 1024), every = range(interval, 10, 3600), retain = range(keep, 1, 500), min = range(battery, 0, 100);
            int bitrate = new int[]{32000, 64000, 96000}[quality.getSelectedItemPosition()];
            boolean historyChanged = memory != prefs.getInt("ram_mb", 16) || diskOn.isChecked() != prefs.getBoolean("disk", false) || diskMb != prefs.getInt("disk_mb", 128) || bitrate != prefs.getInt("bitrate", 32000);
            if (historyChanged && prefs.getBoolean("enabled", false)) { status.setText("Tap STOP on the main screen before changing history or quality."); return; }
            prefs.edit().putInt("ram_mb", memory).putBoolean("disk", diskOn.isChecked()).putInt("disk_mb", diskMb).putInt("bitrate", bitrate)
                .putString("format", formats.get(format.getSelectedItemPosition())).putBoolean("autosave", autosave.isChecked()).putBoolean("autosave_fill", fill.isChecked())
                .putInt("autosave_seconds", every).putInt("auto_keep", retain).putBoolean("schedule", schedule.isChecked())
                .putInt("schedule_from", ListeningRules.minute(from.getText().toString())).putInt("schedule_to", ListeningRules.minute(to.getText().toString()))
                .putInt("battery_min", min).putBoolean("automation", automation.isChecked()).apply();
            if (prefs.getBoolean("enabled", false)) startService(new Intent(this, SaidItService.class).setAction(SaidItService.ACTION_RULES));
            status.setText("Settings saved.");
        } catch (RuntimeException e) { status.setText("Check the number ranges and use HH:mm for times."); }
    }
    private static int range(EditText view, int minimum, int maximum) { int v = Integer.parseInt(view.getText().toString()); if (v < minimum || v > maximum) throw new IllegalArgumentException(); return v; }
    private EditText number(LinearLayout body, String label, int value) { Ui.text(this, body, label, 14); EditText e = new EditText(this); e.setContentDescription(label); e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); e.setText(Integer.toString(value)); body.addView(e); return e; }
    private EditText time(LinearLayout body, String label, int value) { Ui.text(this, body, label, 14); EditText e = new EditText(this); e.setContentDescription(label); e.setSingleLine(true); e.setText(String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60)); body.addView(e); return e; }
    private SwitchMaterial toggle(LinearLayout body, String label, boolean value) { SwitchMaterial s = new SwitchMaterial(this); s.setText(label); s.setChecked(value); body.addView(s); return s; }
    private Spinner spinner(LinearLayout body, List<String> items) { Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items)); body.addView(s); return s; }
    private void updateLocation() { location.setText("Local recordings // private app storage, removed on uninstall\nCopy folder // " + prefs.getString("tree", "None") + "\nFolder copies are independent. Library rename/delete affects the local copy."); }
    private void releaseTree() {
        String old = prefs.getString("tree", "");
        if (!old.isEmpty()) try { getContentResolver().releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (SecurityException ignored) {}
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            int grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(tree, grantFlags);
            if (!tree.toString().equals(prefs.getString("tree", ""))) releaseTree();
            prefs.edit().putString("tree", tree.toString()).apply(); updateLocation();
        } catch (SecurityException e) { status.setText("Folder access could not be retained. Choose another folder."); }
    }
}
