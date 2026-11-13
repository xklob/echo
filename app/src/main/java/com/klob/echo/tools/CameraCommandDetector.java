package com.klob.echo.tools;

import android.content.Context;
import android.os.*;
import com.klob.echo.core.CameraCommand;
import com.klob.echo.speech.SpeechModels;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.vosk.*;

/** Offline phrase commands from the recorder's PCM. No general keyword alerts or text events. */
public final class CameraCommandDetector {
  public enum Status {
    OFF,
    NO_MODEL,
    LOADING,
    LISTENING,
    ERROR
  }

  public interface Listener {
    void onCamera(String facing);
  }

  private final Context context;
  private final Listener listener;
  private volatile Run active;
  private volatile Status status = Status.OFF;

  public CameraCommandDetector(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
  }

  public Status status() {
    return status;
  }

  public boolean isRunning() {
    return active != null;
  }

  public synchronized void start(String back, String front) {
    stop();
    if (!SpeechModels.installed(context)) {
      status = Status.NO_MODEL;
      return;
    }
    status = Status.LOADING;
    Run run = new Run(back, front);
    active = run;
    run.handler.post(run::load);
  }

  public synchronized void stop() {
    Run previous = active;
    active = null;
    status = Status.OFF;
    if (previous != null) {
      previous.handler.post(previous::close);
      previous.thread.quitSafely();
    }
  }

  public void feed(byte[] pcm, int count) {
    Run run = active;
    if (run == null || status != Status.LISTENING) return;
    byte[] copy = java.util.Arrays.copyOf(pcm, count);
    if (!run.queue.offer(copy)) {
      // A gap must reset recognition rather than joining unrelated halves of a command.
      run.queue.clear();
      run.discontinuous.set(true);
      run.queue.offer(copy);
    }
    run.schedule();
  }

  private final class Run {
    final String back, front;
    final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(24);
    final AtomicBoolean pending = new AtomicBoolean(), discontinuous = new AtomicBoolean();
    final HandlerThread thread = new HandlerThread("cameraCommands");
    final Handler handler;
    Recognizer recognizer;
    boolean acquired;
    long lastTrigger = -10000;

    Run(String back, String front) {
      this.back = CameraCommand.normalize(back);
      this.front = CameraCommand.normalize(front);
      thread.start();
      handler = new Handler(thread.getLooper());
    }

    void load() {
      try {
        Model model = SpeechModels.acquire(context);
        acquired = true;
        recognizer =
            new Recognizer(
                model, 48000, new JSONArray().put(back).put(front).put("[unk]").toString());
        if (active == this) status = Status.LISTENING;
      } catch (Exception | OutOfMemoryError e) {
        if (active == this) status = Status.ERROR;
        close();
      }
    }

    void schedule() {
      if (pending.compareAndSet(false, true)) handler.post(this::drain);
    }

    void drain() {
      try {
        if (active != this || recognizer == null) return;
        if (discontinuous.getAndSet(false)) recognizer.reset();
        byte[] pcm;
        for (int i = 0; i < 12 && (pcm = queue.poll()) != null; i++) {
          if (recognizer.acceptWaveForm(pcm, pcm.length)) {
            String text = new JSONObject(recognizer.getResult()).optString("text");
            String facing = CameraCommand.match(text, back, front);
            long now = SystemClock.elapsedRealtime();
            if (facing != null && now - lastTrigger >= 3000 && active == this) {
              lastTrigger = now;
              listener.onCamera(facing);
            }
          }
        }
      } catch (Exception e) {
        if (active == this) status = Status.ERROR;
      } finally {
        pending.set(false);
        if (active == this && !queue.isEmpty()) schedule();
      }
    }

    void close() {
      queue.clear();
      if (recognizer != null) {
        recognizer.close();
        recognizer = null;
      }
      if (acquired) {
        acquired = false;
        SpeechModels.release();
      }
    }
  }
}
