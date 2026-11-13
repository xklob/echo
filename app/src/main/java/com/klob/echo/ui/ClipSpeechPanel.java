package com.klob.echo.ui;

import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.*;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.klob.echo.R;
import com.klob.echo.speech.*;
import java.io.File;
import java.util.*;

/** Speech controls and a paginated, word-seekable transcript. */
final class ClipSpeechPanel extends LinearLayout {
  private static final int PAGE = 250;
  private final TextView summary, status, transcript, pageLabel;
  private final MaterialButton analyze, transcribe, cancel, share, previous, next;
  private final AudioPlayback player;
  private ClipAnalysis shown;
  private int page;

  ClipSpeechPanel(Fragment owner, ClipAnalysisModel model, File file, AudioPlayback player) {
    super(owner.requireContext());
    this.player = player;
    setOrientation(VERTICAL);
    Ui.heading(this, R.string.clip_speech);
    summary = Ui.text(this, R.string.speech_not_analyzed, 15, true);
    status = Ui.text(this, "", 14, true);
    analyze = Ui.button(this, R.string.analyze_speech, false, () -> model.request("speech"));
    analyze.setId(R.id.analyze_speech);
    transcribe =
        Ui.button(
            this,
            R.string.transcribe,
            true,
            () -> {
              if (SpeechModels.installed(getContext())) model.request("transcript");
              else DetailActivity.open(getContext(), "tool_speech", R.string.speech_settings);
            });
    transcribe.setId(R.id.transcribe_clip);
    cancel = Ui.button(this, R.string.cancel_analysis, false, model::cancel);
    Ui.row(
        this,
        R.string.speech_settings,
        R.string.speech_settings_help,
        () -> DetailActivity.open(getContext(), "tool_speech", R.string.speech_settings));
    Ui.heading(this, R.string.transcript);
    Ui.text(this, R.string.transcript_help, 13, true);
    transcript = Ui.text(this, R.string.transcript_empty, 16, false);
    transcript.setId(R.id.clip_transcript);
    transcript.setMovementMethod(LinkMovementMethod.getInstance());
    pageLabel = Ui.text(this, "", 13, true);
    previous =
        Ui.button(
            this,
            R.string.previous_page,
            false,
            () -> {
              page--;
              text();
            });
    next =
        Ui.button(
            this,
            R.string.next_page,
            false,
            () -> {
              page++;
              text();
            });
    share =
        Ui.button(
            this,
            R.string.share_transcript,
            false,
            () -> FileActions.share(owner, ClipAnalysis.file(file, ".transcript.txt")));
  }

  void render(ClipAnalysisModel.Display display) {
    ClipAnalysis result = display.analysis;
    long total = 0;
    for (long[] range : result.speech) total += range[1] - range[0];
    summary.setText(
        !result.analyzed
            ? getContext().getString(R.string.speech_not_analyzed)
            : result.speech.isEmpty()
                ? getContext().getString(R.string.speech_none)
                : getContext().getString(R.string.speech_found, LibraryText.duration(total)));
    status.setText(display.status);
    status.setVisibility(display.status.isEmpty() ? GONE : VISIBLE);
    analyze.setEnabled(!display.busy);
    transcribe.setEnabled(!display.busy);
    cancel.setVisibility(display.busy ? VISIBLE : GONE);
    analyze.setText(result.analyzed ? R.string.analyze_again : R.string.analyze_speech);
    transcribe.setText(result.transcribed ? R.string.transcribe_again : R.string.transcribe);
    if (shown != result) {
      shown = result;
      page = 0;
      text();
    }
    player.analysis(display.waveform, result.speech);
  }

  private void text() {
    int pages = Math.max(1, (shown.words.size() + PAGE - 1) / PAGE);
    page = Math.max(0, Math.min(pages - 1, page));
    SpannableStringBuilder text = new SpannableStringBuilder();
    long prior = -1;
    for (int i = page * PAGE; i < Math.min(shown.words.size(), (page + 1) * PAGE); i++) {
      ClipAnalysis.Word word = shown.words.get(i);
      if (text.length() > 0) text.append(word.start - prior > 1500 ? "\n\n" : " ");
      int start = text.length();
      text.append(word.text);
      text.setSpan(
          new ClickableSpan() {
            @Override
            public void onClick(View view) {
              player.seekTo(word.start);
            }

            @Override
            public void updateDrawState(TextPaint paint) {
              paint.setColor(
                  Ui.color(getContext(), com.google.android.material.R.attr.colorOnSurface));
              paint.setUnderlineText(false);
            }
          },
          start,
          text.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      prior = word.end;
    }
    transcript.setText(
        text.length() > 0
            ? text
            : getContext()
                .getString(
                    shown.transcribed ? R.string.transcript_no_words : R.string.transcript_empty));
    pageLabel.setText(getContext().getString(R.string.transcript_page, page + 1, pages));
    pageLabel.setVisibility(pages > 1 ? VISIBLE : GONE);
    previous.setVisibility(page > 0 ? VISIBLE : GONE);
    next.setVisibility(page + 1 < pages ? VISIBLE : GONE);
    share.setVisibility(shown.transcribed && !shown.words.isEmpty() ? VISIBLE : GONE);
  }
}
