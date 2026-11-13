package com.klob.echo.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import androidx.core.view.*;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.*;
import com.klob.echo.R;
import java.util.Locale;

/** Shared spacing and native Material components. All dimensions are density independent. */
final class Ui {
  private Ui() {}

  static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  static int color(Context c, int attr) {
    return MaterialColors.getColor(c, attr, "Echo");
  }

  static LinearLayout column(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  static NestedScrollView scroll(Context c, LinearLayout body) {
    NestedScrollView scroll = new NestedScrollView(c);
    scroll.setFillViewport(true);
    int width = c.getResources().getConfiguration().screenWidthDp;
    int side = dp(c, Math.max(20, (width - 720) / 2));
    body.setPadding(side, dp(c, 12), side, dp(c, 28));
    scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
    return scroll;
  }

  static void insets(android.app.Activity a) {
    WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
    View root = a.findViewById(R.id.root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
          v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime));
          return WindowInsetsCompat.CONSUMED;
        });
    boolean light =
        (a.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    WindowInsetsControllerCompat control = WindowCompat.getInsetsController(a.getWindow(), root);
    control.setAppearanceLightStatusBars(light);
    control.setAppearanceLightNavigationBars(light);
  }

  static TextView text(LinearLayout parent, CharSequence text, int size, boolean muted) {
    Context c = parent.getContext();
    TextView v = new TextView(c);
    v.setText(text);
    v.setTextSize(size);
    v.setLineSpacing(dp(c, 2), 1);
    v.setTextColor(
        color(
            c,
            muted
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(c, 8);
    parent.addView(v, lp);
    return v;
  }

  static TextView text(LinearLayout p, int res, int size, boolean muted) {
    return text(p, p.getContext().getText(res), size, muted);
  }

  static TextView heading(LinearLayout p, int title) {
    TextView v = text(p, title, 20, false);
    v.setTypeface(v.getTypeface(), Typeface.BOLD);
    v.setPadding(0, dp(p.getContext(), 20), 0, dp(p.getContext(), 4));
    ViewCompat.setAccessibilityHeading(v, true);
    return v;
  }

  static LinearLayout card(LinearLayout parent) {
    Context c = parent.getContext();
    MaterialCardView card = new MaterialCardView(c);
    card.setRadius(dp(c, 20));
    card.setCardElevation(0);
    card.setStrokeWidth(dp(c, 1));
    card.setStrokeColor(color(c, com.google.android.material.R.attr.colorOutlineVariant));
    card.setCardBackgroundColor(color(c, com.google.android.material.R.attr.colorSurface));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(c, 16);
    parent.addView(card, lp);
    LinearLayout body = column(c);
    body.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 16));
    card.addView(body);
    return body;
  }

  static MaterialButton button(LinearLayout parent, int label, boolean primary, Runnable action) {
    Context c = parent.getContext();
    MaterialButton b =
        new MaterialButton(
            c,
            null,
            primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
    b.setText(label);
    b.setAllCaps(primary);
    b.setMinHeight(dp(c, 52));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(c, 4);
    lp.bottomMargin = dp(c, 4);
    parent.addView(b, lp);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  static View row(LinearLayout parent, int title, CharSequence detail, Runnable action) {
    return row(parent, parent.getContext().getString(title), detail, action);
  }

  static View row(LinearLayout parent, CharSequence title, CharSequence detail, Runnable action) {
    Context c = parent.getContext();
    LinearLayout row = new LinearLayout(c);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(c, 4), dp(c, 16), dp(c, 4), dp(c, 16));
    row.setMinimumHeight(dp(c, 72));
    LinearLayout labels = column(c);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    TextView t = text(labels, title, 16, false);
    ((LinearLayout.LayoutParams) t.getLayoutParams()).bottomMargin = dp(c, 4);
    TextView subtitle = text(labels, detail, 14, true);
    ((LinearLayout.LayoutParams) subtitle.getLayoutParams()).bottomMargin = 0;
    ImageView arrow = new ImageView(c);
    arrow.setImageResource(R.drawable.ic_chevron);
    arrow.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            color(c, com.google.android.material.R.attr.colorOnSurfaceVariant)));
    arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(arrow, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
    android.util.TypedValue value = new android.util.TypedValue();
    c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
    row.setBackgroundResource(value.resourceId);
    row.setFocusable(true);
    row.setOnClickListener(v -> action.run());
    row.setContentDescription(title + ". " + detail);
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return row;
  }

  static View row(LinearLayout p, int title, int detail, Runnable action) {
    return row(p, title, p.getContext().getString(detail), action);
  }

  static MaterialSwitch toggle(
      LinearLayout p,
      int title,
      int detail,
      boolean checked,
      java.util.function.Consumer<Boolean> change) {
    Context c = p.getContext();
    MaterialSwitch s = new MaterialSwitch(c);
    s.setText(title);
    s.setTextSize(16);
    s.setMinHeight(dp(c, 56));
    s.setChecked(checked);
    s.setSwitchPadding(dp(c, 16));
    p.addView(s, new LinearLayout.LayoutParams(-1, -2));
    if (detail != 0) text(p, detail, 14, true);
    s.setOnCheckedChangeListener((b, on) -> change.accept(on));
    return s;
  }

  static TextInputLayout input(LinearLayout p, int label, CharSequence value, int type) {
    Context c = p.getContext();
    TextInputLayout layout =
        new TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(c.getString(label));
    TextInputEditText e = new TextInputEditText(layout.getContext());
    e.setInputType(type);
    e.setText(value);
    e.setTextSize(16);
    layout.addView(e, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(c, 12);
    lp.bottomMargin = dp(c, 12);
    p.addView(layout, lp);
    return layout;
  }

  static void setText(TextView v, CharSequence text) {
    if (v != null && !v.getText().toString().contentEquals(text)) v.setText(text);
  }

  static String duration(long samples) {
    long seconds = Math.max(0, samples / 48000);
    return seconds >= 3600
        ? String.format(
            Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }

  static String bytes(long bytes) {
    return bytes >= 1048576
        ? String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
        : String.format(Locale.getDefault(), "%d KB", Math.max(1, bytes / 1024));
  }
}
