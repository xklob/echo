package com.klob.echo.ui;

import android.content.*;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.timepicker.*;
import com.klob.echo.*;
import com.klob.echo.recording.*;
import com.klob.echo.settings.AppSettings;
import java.util.*;
import java.util.function.Supplier;

public final class SettingsDetailFragment extends ServiceFragment {
  private LinearLayout body;
  private TextView pending;
  private final Map<TextView, Supplier<String>> values = new LinkedHashMap<>();
  private final ActivityResultLauncher<Uri> folder =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocumentTree(),
          uri -> {
            if (uri == null) return;
            try {
              requireContext()
                  .getContentResolver()
                  .takePersistableUriPermission(
                      uri,
                      Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
              releaseFolder(uri.toString());
              prefs().edit().putString("tree", uri.toString()).apply();
              message(R.string.folder_saved);
            } catch (RuntimeException e) {
              message(R.string.folder_failed);
            }
          });

  @Override
  public View onCreateView(LayoutInflater i, ViewGroup c, Bundle state) {
    body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    String page = requireArguments().getString("page", "history");
    values.clear();
    if (page.equals("history")) {
      pending = Ui.text(body, R.string.pending_settings, 14, true);
      pending.setId(R.id.history_pending);
    }
    switch (page) {
      case "history":
        Ui.text(body, R.string.rolling_history_explanation, 16, true);
        Ui.text(body, getString(R.string.memory_help, RecordingService.maxRamMb()), 14, true);
        number(
            "ram_mb",
            R.string.memory_budget,
            0,
            16,
            1,
            RecordingService.maxRamMb(),
            true,
            R.string.mb_value);
        Ui.toggle(
                body,
                R.string.disk_history,
                R.string.disk_history_help,
                prefs().getBoolean("disk", false),
                on -> AppSettings.save(requireContext(), "disk", on, true))
            .setId(R.id.setting_disk);
        number(
            "disk_mb",
            R.string.disk_budget,
            R.string.disk_help,
            128,
            16,
            1024,
            true,
            R.string.mb_value);
        choice(
            "bitrate",
            R.string.quality,
            R.string.quality_help,
            new int[] {R.string.quality_standard, R.string.quality_high, R.string.quality_highest},
            new String[] {"32000", "64000", "96000"},
            "32000",
            true,
            true);
        List<String> formats = new ArrayList<>(Arrays.asList("opus", "wav"));
        List<Integer> labels =
            new ArrayList<>(Arrays.asList(R.string.format_opus, R.string.format_wav));
        if (PlatformEncoder.supported(MediaFormat.MIMETYPE_AUDIO_FLAC)) {
          formats.add("flac");
          labels.add(R.string.format_flac);
        }
        if (PlatformEncoder.supported(MediaFormat.MIMETYPE_AUDIO_AAC)) {
          formats.add("m4a");
          labels.add(R.string.format_aac);
        }
        choice(
            "format",
            R.string.format,
            R.string.format_help,
            labels.stream().mapToInt(Integer::intValue).toArray(),
            formats.toArray(new String[0]),
            "opus",
            false,
            false);
        break;
      case "automatic":
        Ui.text(body, R.string.rolling_save_explanation, 16, true);
        toggle("autosave", R.string.autosave_enable, R.string.autosave_help, false);
        toggle("autosave_fill", R.string.autosave_fill, R.string.autosave_fill_help, false);
        number(
            "autosave_seconds",
            R.string.interval,
            R.string.interval_help,
            300,
            10,
            3600,
            false,
            R.string.seconds_value);
        number("auto_keep", R.string.retention, R.string.retention_help, 48, 1, 500, false, 0);
        Ui.text(body, R.string.retention_help, 14, true);
        break;
      case "rules":
        toggle("schedule", R.string.schedule_enable, R.string.schedule_help, false);
        time("schedule_from", R.string.schedule_from);
        time("schedule_to", R.string.schedule_to);
        number(
            "battery_min",
            R.string.battery_min,
            R.string.battery_help,
            0,
            0,
            100,
            false,
            R.string.percent_value);
        Ui.text(body, R.string.schedule_note, 14, true);
        if (android.os.Build.VERSION.SDK_INT >= 31)
          Ui.row(
              body,
              R.string.exact_alarm,
              R.string.schedule_note,
              () -> {
                try {
                  startActivity(
                      new Intent(
                          android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                          Uri.parse("package:" + requireContext().getPackageName())));
                } catch (RuntimeException ignored) {
                  message(R.string.schedule_note);
                }
              });
        break;
      case "storage":
        Ui.text(body, R.string.folder_help, 16, true);
        valueRow(
            R.string.storage,
            () -> {
              String tree = prefs().getString("tree", "");
              return tree.isEmpty()
                  ? getString(R.string.folder_local)
                  : android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            },
            () -> folder.launch(null));
        Ui.button(body, R.string.folder_choose, true, () -> folder.launch(null));
        Ui.button(
            body,
            R.string.folder_remove,
            false,
            () -> {
              releaseFolder("");
              prefs().edit().remove("tree").apply();
            });
        break;
      case "automation":
        toggle("automation", R.string.automation_enable, R.string.automation_note, false);
        break;
      case "about":
        Ui.text(body, R.string.app_name, 32, false);
        Ui.text(
            body,
            getString(R.string.version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            16,
            true);
        Ui.text(body, R.string.about_text, 16, true);
        Ui.row(body, R.string.licenses, "GPL-3.0 · echo and contributors", this::licenses);
        Ui.row(
            body,
            R.string.diagnostics,
            R.string.diagnostics_help,
            () -> {
              if (service != null)
                try {
                  startActivity(
                      Intent.createChooser(
                          new Intent(Intent.ACTION_SEND)
                              .setType("text/plain")
                              .putExtra(Intent.EXTRA_TEXT, service.diagnosticReport()),
                          getString(R.string.diagnostics)));
                } catch (RuntimeException e) {
                  message(R.string.share_failed);
                }
            });
        break;
      default:
        throw new IllegalArgumentException("Unknown settings page");
    }
    return root;
  }

  private void toggle(String key, int title, int help, boolean next) {
    Ui.toggle(
        body,
        title,
        help,
        prefs().getBoolean(key, false),
        on -> AppSettings.save(requireContext(), key, on, next));
  }

  private void number(
      String key, int title, int help, int fallback, int min, int max, boolean next, int format) {
    valueRow(
        title,
        () ->
            format == 0
                ? Integer.toString(prefs().getInt(key, fallback))
                : getString(format, prefs().getInt(key, fallback)),
        () ->
            PreferenceDialog.number(key, title, help, fallback, min, max, next)
                .show(getParentFragmentManager(), "value"));
  }

  private void choice(
      String key,
      int title,
      int help,
      int[] labelIds,
      String[] options,
      String fallback,
      boolean numeric,
      boolean next) {
    String[] labels = Arrays.stream(labelIds).mapToObj(this::getString).toArray(String[]::new);
    valueRow(
        title,
        () -> {
          String current =
              numeric
                  ? Integer.toString(prefs().getInt(key, Integer.parseInt(fallback)))
                  : prefs().getString(key, fallback);
          int index = Arrays.asList(options).indexOf(current);
          return labels[Math.max(0, index)];
        },
        () ->
            PreferenceDialog.choice(key, title, help, labels, options, fallback, numeric, next)
                .show(getParentFragmentManager(), "choice"));
    if (help != 0) Ui.text(body, help, 14, true);
  }

  private void valueRow(int title, Supplier<String> value, Runnable action) {
    LinearLayout row = (LinearLayout) Ui.row(body, title, value.get(), action);
    TextView text = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
    values.put(
        text,
        () -> {
          String v = value.get();
          row.setContentDescription(getString(title) + ". " + v);
          return v;
        });
  }

  private void time(String key, int title) {
    valueRow(
        title,
        () -> {
          int mins = prefs().getInt(key, 0);
          Calendar cal = Calendar.getInstance();
          cal.set(Calendar.HOUR_OF_DAY, mins / 60);
          cal.set(Calendar.MINUTE, mins % 60);
          return DateFormat.getTimeFormat(requireContext()).format(cal.getTime());
        },
        () -> {
          int mins = prefs().getInt(key, 0);
          MaterialTimePicker picker =
              new MaterialTimePicker.Builder()
                  .setTitleText(title)
                  .setTimeFormat(
                      DateFormat.is24HourFormat(requireContext())
                          ? TimeFormat.CLOCK_24H
                          : TimeFormat.CLOCK_12H)
                  .setHour(mins / 60)
                  .setMinute(mins % 60)
                  .build();
          picker.addOnPositiveButtonClickListener(
              v ->
                  AppSettings.save(
                      requireContext(), key, picker.getHour() * 60 + picker.getMinute(), false));
          picker.show(getChildFragmentManager(), key);
        });
  }

  private void releaseFolder(String replacement) {
    String old = prefs().getString("tree", "");
    if (old.isEmpty() || old.equals(replacement)) return;
    try {
      requireContext()
          .getContentResolver()
          .releasePersistableUriPermission(
              Uri.parse(old),
              Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (RuntimeException ignored) {
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    // Material restores the picker itself after rotation; reconnect its save action too.
    for (String key : new String[] {"schedule_from", "schedule_to"}) {
      androidx.fragment.app.Fragment restored = getChildFragmentManager().findFragmentByTag(key);
      if (restored instanceof MaterialTimePicker) {
        MaterialTimePicker picker = (MaterialTimePicker) restored;
        picker.clearOnPositiveButtonClickListeners();
        picker.addOnPositiveButtonClickListener(
            v ->
                AppSettings.save(
                    requireContext(), key, picker.getHour() * 60 + picker.getMinute(), false));
      }
    }
  }

  private void licenses() {
    new LicensesDialog().show(getParentFragmentManager(), "licenses");
  }

  @Override
  protected void render() {
    for (Map.Entry<TextView, Supplier<String>> item : values.entrySet())
      Ui.setText(item.getKey(), item.getValue().get());
    if (pending != null)
      pending.setVisibility(
          prefs().getBoolean("history_settings_pending", false) ? View.VISIBLE : View.GONE);
  }
}
