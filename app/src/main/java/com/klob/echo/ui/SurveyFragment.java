package com.klob.echo.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.klob.echo.R;
import com.klob.echo.storage.Io;
import com.klob.echo.tools.Survey;
import java.io.File;
import java.util.*;

public final class SurveyFragment extends Fragment {
  private Survey survey;
  private String json, current, draft = "";
  private final ArrayList<Survey.Answer> answers = new ArrayList<>();
  private final ArrayList<String> visited = new ArrayList<>();
  private LinearLayout body;
  private TextInputLayout text;
  private RadioGroup choices;
  private Slider scale;
  private ToolJobs jobs;
  private MaterialButton save;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    jobs = new ViewModelProvider(this).get(ToolJobs.class);
    try {
      json =
          state == null
              ? Io.read(new File(requireContext().getFilesDir(), "survey.json").toPath())
              : state.getString("json");
      survey = Survey.parse(json);
      current = state == null ? survey.start : state.getString("current");
      draft = state == null ? "" : state.getString("draft", "");
      if (state != null) {
        ArrayList<String> ids = state.getStringArrayList("ids"),
            values = state.getStringArrayList("answers");
        if (ids != null && values != null)
          for (int i = 0; i < Math.min(ids.size(), values.size()); i++) {
            Survey.Node n = survey.node(ids.get(i));
            if (n != null) {
              visited.add(n.id);
              answers.add(new Survey.Answer(n.id, n.question, values.get(i)));
            }
          }
      }
    } catch (Exception e) {
      survey = null;
    }
  }

  @Override
  public View onCreateView(LayoutInflater i, ViewGroup c, Bundle state) {
    body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    show();
    return root;
  }

  @Override
  public void onViewCreated(View view, Bundle state) {
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(
            getViewLifecycleOwner(),
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                previous();
              }
            });
    jobs.busy.observe(
        getViewLifecycleOwner(),
        busy -> {
          if (save != null) save.setEnabled(!busy);
        });
    jobs.result.observe(
        getViewLifecycleOwner(),
        result -> {
          if (result == 0) return;
          if (result == R.string.survey_saved) {
            Toast.makeText(requireContext(), result, Toast.LENGTH_SHORT).show();
            requireActivity().finish();
          } else Snackbar.make(view, result, Snackbar.LENGTH_LONG).show();
        });
  }

  private void show() {
    body.removeAllViews();
    text = null;
    choices = null;
    scale = null;
    save = null;
    if (survey == null) {
      Ui.text(body, R.string.survey_invalid, 18, true);
      return;
    }
    Ui.text(body, survey.title, 16, true);
    Survey.Node node = survey.node(current);
    if (node == null || node.type.equals(Survey.TYPE_END)) {
      Ui.text(body, R.string.survey_complete, 26, false);
      if (node != null && !node.question.isEmpty()) Ui.text(body, node.question, 18, false);
      save =
          Ui.button(
              body,
              R.string.survey_finish,
              true,
              () -> jobs.answers(survey.toJson(answers, System.currentTimeMillis())));
      if (!visited.isEmpty()) Ui.button(body, R.string.survey_previous, false, this::previous);
      return;
    }
    Ui.text(body, getString(R.string.survey_question, answers.size() + 1), 14, true);
    Ui.text(body, node.question, 26, false);
    if (!node.hint.isEmpty()) Ui.text(body, node.hint, 16, true);
    if (node.type.equals(Survey.TYPE_CHOICE)) {
      choices = new RadioGroup(requireContext());
      body.addView(choices);
      for (Survey.Option option : node.options) {
        MaterialRadioButton b = new MaterialRadioButton(requireContext());
        b.setId(View.generateViewId());
        b.setText(option.label);
        b.setMinHeight(Ui.dp(requireContext(), 56));
        choices.addView(b);
        if (option.label.equals(draft)) b.setChecked(true);
      }
    } else if (node.type.equals(Survey.TYPE_SCALE)) {
      int value = node.min;
      try {
        value = Integer.parseInt(draft);
      } catch (NumberFormatException ignored) {
      }
      value = Math.max(node.min, Math.min(node.max, value));
      TextView selected = Ui.text(body, Integer.toString(value), 32, false);
      if (node.min < node.max) {
        scale = new Slider(requireContext());
        scale.setValueFrom(node.min);
        scale.setValueTo(node.max);
        scale.setStepSize(1);
        scale.setValue(value);
        scale.setContentDescription(node.question);
        scale.addOnChangeListener((s, v, user) -> Ui.setText(selected, Integer.toString((int) v)));
        body.addView(scale);
      }
      Ui.text(body, node.minLabel + "  ·  " + node.maxLabel, 14, true);
    } else {
      text =
          Ui.input(
              body,
              R.string.survey_answer,
              draft,
              InputType.TYPE_CLASS_TEXT
                  | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                  | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
      text.getEditText().setMinLines(3);
      text.setCounterEnabled(true);
      text.setCounterMaxLength(4096);
    }
    MaterialButton next =
        Ui.button(
            body,
            R.string.survey_next,
            true,
            () -> {
              String value = answerValue(node), nextId = node.next;
              if (node.type.equals(Survey.TYPE_CHOICE)) {
                int id = choices.getCheckedRadioButtonId();
                if (id == -1) return;
                String label = ((RadioButton) choices.findViewById(id)).getText().toString();
                for (Survey.Option option : node.options)
                  if (option.label.equals(label)) {
                    nextId = option.next;
                    break;
                  }
              }
              if (value.length() > 4096) {
                if (text != null) text.setError(getString(R.string.text_limit, 4096));
                return;
              }
              if (answers.size() >= 100) {
                Snackbar.make(requireView(), R.string.survey_limit, Snackbar.LENGTH_LONG).show();
                return;
              }
              visited.add(node.id);
              answers.add(new Survey.Answer(node.id, node.question, value));
              current = nextId;
              draft = "";
              show();
            });
    if (choices != null) {
      next.setEnabled(choices.getCheckedRadioButtonId() != -1);
      choices.setOnCheckedChangeListener((group, id) -> next.setEnabled(id != -1));
    }
    if (!visited.isEmpty()) Ui.button(body, R.string.survey_previous, false, this::previous);
  }

  private String answerValue(Survey.Node node) {
    if (text != null) return text.getEditText().getText().toString();
    if (choices != null) {
      RadioButton selected = choices.findViewById(choices.getCheckedRadioButtonId());
      return selected == null ? "" : selected.getText().toString();
    }
    return scale == null ? Integer.toString(node.min) : Integer.toString((int) scale.getValue());
  }

  private void previous() {
    if (visited.isEmpty()) {
      requireActivity().finish();
      return;
    }
    int last = visited.size() - 1;
    current = visited.remove(last);
    draft = answers.remove(last).answer;
    show();
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    state.putString("json", json);
    state.putString("current", current);
    Survey.Node node = survey == null ? null : survey.node(current);
    state.putString("draft", node == null ? "" : answerValue(node));
    state.putStringArrayList("ids", visited);
    ArrayList<String> values = new ArrayList<>();
    for (Survey.Answer answer : answers) values.add(answer.answer);
    state.putStringArrayList("answers", values);
    super.onSaveInstanceState(state);
  }
}
