package com.klob.echo.ui;

import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationBarView;
import com.klob.echo.R;

public final class MainActivity extends AppCompatActivity {
  private NavigationBarView navigation;
  private boolean serviceBound;
  private final android.content.ServiceConnection keeper =
      new android.content.ServiceConnection() {
        public void onServiceConnected(
            android.content.ComponentName name, android.os.IBinder binder) {}

        public void onServiceDisconnected(android.content.ComponentName name) {}
      };

  @Override
  protected void onStart() {
    super.onStart();
    serviceBound =
        bindService(
            new android.content.Intent(this, com.klob.echo.recording.RecordingService.class),
            keeper,
            BIND_AUTO_CREATE);
  }

  @Override
  protected void onStop() {
    if (serviceBound) {
      unbindService(keeper);
      serviceBound = false;
    }
    super.onStop();
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    Ui.insets(this);
    MaterialToolbar toolbar = findViewById(R.id.toolbar);
    toolbar
        .getMenu()
        .add(0, R.id.theme_choice, 0, R.string.appearance)
        .setIcon(R.drawable.ic_appearance)
        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
    toolbar.setOnMenuItemClickListener(
        item -> {
          PreferenceDialog.theme().show(getSupportFragmentManager(), "theme");
          return true;
        });
    toolbar
        .getMenu()
        .findItem(R.id.theme_choice)
        .getIcon()
        .setTint(Ui.color(this, com.google.android.material.R.attr.colorOnSurface));
    navigation = findViewById(R.id.navigation);
    navigation.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(
            Ui.color(this, com.google.android.material.R.attr.colorSurface)));
    navigation.setElevation(0);
    navigation.setOnItemSelectedListener(
        item -> {
          show(item.getItemId());
          return true;
        });
    int selected = state == null ? R.id.nav_record : state.getInt("destination", R.id.nav_record);
    navigation.setSelectedItemId(selected);
    if (getSupportFragmentManager().findFragmentById(R.id.content) == null) show(selected);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (navigation.getSelectedItemId() != R.id.nav_record)
                  navigation.setSelectedItemId(R.id.nav_record);
                else {
                  setEnabled(false);
                  getOnBackPressedDispatcher().onBackPressed();
                  setEnabled(true);
                }
              }
            });
  }

  public void selectLibrary() {
    navigation.setSelectedItemId(R.id.nav_library);
  }

  private void show(int id) {
    String tag = Integer.toString(id);
    Fragment existing = getSupportFragmentManager().findFragmentById(R.id.content);
    if (existing == null || !tag.equals(existing.getTag())) {
      Fragment next =
          id == R.id.nav_library
              ? new LibraryFragment()
              : id == R.id.nav_tools
                  ? new ToolsFragment()
                  : id == R.id.nav_settings ? new SettingsFragment() : new RecordFragment();
      getSupportFragmentManager().beginTransaction().replace(R.id.content, next, tag).commit();
    }
    ((MaterialToolbar) findViewById(R.id.toolbar))
        .setTitle(
            id == R.id.nav_library
                ? R.string.nav_library
                : id == R.id.nav_tools
                    ? R.string.nav_tools
                    : id == R.id.nav_settings ? R.string.nav_settings : R.string.app_name);
  }

  @Override
  protected void onNewIntent(android.content.Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.getBooleanExtra("tile_start", false)) navigation.setSelectedItemId(R.id.nav_record);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putInt("destination", navigation.getSelectedItemId());
    super.onSaveInstanceState(out);
  }
}
