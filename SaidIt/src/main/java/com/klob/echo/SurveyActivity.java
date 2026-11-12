package com.klob.echo;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** The copa-sh branching questionnaire format with a compact Material presentation. */
public final class SurveyActivity extends AppCompatActivity {
  private Survey survey;
  private final List<Survey.Answer> answers = new ArrayList<>();
  private final List<String> visited = new ArrayList<>();
  private LinearLayout body;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      survey = Survey.parse(Io.read(new File(getFilesDir(), "survey.json").toPath()));
      if (state != null) {
        ArrayList<String> ids = state.getStringArrayList("ids"),
            values = state.getStringArrayList("answers");
        if (ids != null && values != null)
          for (int i = 0; i < ids.size(); i++) {
            Survey.Node node = survey.node(ids.get(i));
            if (node != null) {
              visited.add(node.id);
              answers.add(new Survey.Answer(node.id, node.question, values.get(i)));
            }
          }
        show(state.getString("current", survey.start));
      } else show(survey.start);
    } catch (Exception e) {
      body = Ui.screen(this);
      Ui.text(this, body, "Import a valid survey JSON in Optional tools first.", 18);
    }
  }

  private String current;

  private void show(String id) {
    current = id;
    Survey.Node node = survey.node(id);
    body = Ui.screen(this);
    Ui.text(this, body, survey.title, 24);
    Ui.text(this, body, "Question " + (answers.size() + 1), 14);
    if (node == null || node.type.equals(Survey.TYPE_END)) {
      if (node != null) Ui.text(this, body, node.question, 18);
      Ui.button(this, body, "SAVE ANSWERS", this::save);
      return;
    }
    Ui.text(this, body, node.question, 20);
    if (!node.hint.isEmpty()) Ui.text(this, body, node.hint, 14);
    if (node.type.equals(Survey.TYPE_CHOICE))
      for (Survey.Option option : node.options)
        Ui.button(this, body, option.label, () -> answer(node, option.label, option.next));
    else if (node.type.equals(Survey.TYPE_SCALE))
      for (int i = node.min; i <= node.max; i++) {
        final int value = i;
        Ui.button(
            this,
            body,
            Integer.toString(i),
            () -> answer(node, Integer.toString(value), node.next));
      }
    else {
      EditText text = new EditText(this);
      body.addView(text);
      Ui.button(this, body, "NEXT", () -> answer(node, text.getText().toString(), node.next));
    }
    Ui.button(
        this,
        body,
        "BACK",
        () -> {
          if (visited.isEmpty()) {
            finish();
            return;
          }
          int i = visited.size() - 1;
          String previous = visited.remove(i);
          answers.remove(i);
          show(previous);
        });
  }

  private void answer(Survey.Node node, String value, String next) {
    if (answers.size() >= 100 || value.length() > 4096) {
      Toast.makeText(this, "Survey answer limit reached.", Toast.LENGTH_LONG).show();
      return;
    }
    visited.add(node.id);
    answers.add(new Survey.Answer(node.id, node.question, value));
    show(next);
  }

  private void save() {
    new Thread(
            () -> {
              try {
                new TraceStore(this)
                    .text("_survey", ".json", survey.toJson(answers, System.currentTimeMillis()));
                runOnUiThread(this::finish);
              } catch (IOException e) {
                runOnUiThread(
                    () ->
                        Toast.makeText(this, "Could not save answers.", Toast.LENGTH_LONG).show());
              }
            },
            "Echo survey save")
        .start();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("current", current);
    state.putStringArrayList("ids", new ArrayList<>(visited));
    ArrayList<String> values = new ArrayList<>();
    for (Survey.Answer answer : answers) values.add(answer.answer);
    state.putStringArrayList("answers", values);
  }
}
