package com.klob.echo;
import android.app.Activity;
import android.view.*;
import android.widget.*;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
final class Ui {
    static LinearLayout screen(Activity activity) {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24, 16, 24, 24);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.addView(body);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        activity.setContentView(scroll); return body;
    }
    static TextView text(Activity a, LinearLayout body, String value, int size) {
        TextView view = new TextView(a); view.setText(value); view.setTextSize(size); view.setPadding(0, 12, 0, 12); body.addView(view); return view;
    }
    static MaterialButton button(Activity a, LinearLayout body, String title, Runnable action) {
        MaterialButton b = new MaterialButton(a); b.setText(title); b.setOnClickListener(v -> action.run()); body.addView(b); return b;
    }
    static String duration(long samples) { long seconds = Math.max(0, samples / 48000); return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
}
