package com.klob.echo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.klob.echo.R;

public final class DetailActivity extends AppCompatActivity {
  public static void open(Context context, String page, int title) {
    context.startActivity(intent(context, page, title));
  }

  public static Intent intent(Context context, String page, int title) {
    return new Intent(context, DetailActivity.class)
        .putExtra("page", page)
        .putExtra("title", title);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_detail);
    Ui.insets(this);
    MaterialToolbar toolbar = findViewById(R.id.toolbar);
    toolbar.setTitle(getIntent().getIntExtra("title", R.string.app_name));
    toolbar.setNavigationIcon(R.drawable.ic_back);
    toolbar.setNavigationContentDescription(R.string.back);
    toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    if (state == null) {
      String page = getIntent().getStringExtra("page");
      if (page == null) page = "history";
      Fragment f =
          page.equals("survey_run")
              ? new SurveyFragment()
              : page.startsWith("tool_") ? new ToolDetailFragment() : new SettingsDetailFragment();
      Bundle args = new Bundle();
      args.putString("page", page);
      f.setArguments(args);
      getSupportFragmentManager().beginTransaction().replace(R.id.content, f).commit();
    }
  }
}
