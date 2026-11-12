package com.klob.echo;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CancellationException;
import com.klob.echo.core.ClipEncoder;

/** Saved files and disposable history have disjoint roots. Local completion is an atomic rename.
 * A SAF directory is an optional additional copy; failure never silently changes the destination. */
final class ClipStore {
    final File root;
    private final Context context;
    ClipStore(Context context) { this.context = context.getApplicationContext(); root = new File(context.getFilesDir(), "clips"); }
    synchronized File temporary(long startMs, String kind, String extension) throws IOException {
        File folder = new File(root, kind);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create recordings folder");
        String name = "Echo-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS", Locale.ROOT).format(new Date(startMs));
        return File.createTempFile(name + "-", "." + extension + ".part", folder);
    }
    File complete(File partial) throws IOException {
        File complete = new File(partial.getParentFile(), partial.getName().substring(0, partial.getName().length() - 5));
        Files.move(partial.toPath(), complete.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return complete;
    }
    List<File> list() {
        List<File> files = new ArrayList<>();
        for (String kind : new String[]{"manual", "auto", "continuous", "activity"}) {
            File[] entries = new File(root, kind).listFiles();
            if (entries != null) for (File entry : entries) if (entry.isFile() && (entry.getName().endsWith(".opus") || entry.getName().endsWith(".wav") || entry.getName().endsWith(".flac") || entry.getName().endsWith(".m4a"))) files.add(entry);
        }
        files.sort(Comparator.comparingLong(File::lastModified).reversed()); return files;
    }
    void rotate(String kind, int keep) throws IOException {
        if (kind.equals("manual")) return;
        File[] files = new File(root, kind).listFiles((d, n) -> !n.endsWith(".part"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = Math.max(1, keep); i < files.length; i++) if (!files[i].delete()) throw new IOException("Cannot enforce automatic clip retention");
    }
    void cleanPartials() {
        for (String kind : new String[]{"manual", "auto", "continuous", "activity"}) {
            File[] files = new File(root, kind).listFiles((d, n) -> n.endsWith(".part"));
            if (files != null) for (File f : files) f.delete();
        }
    }
    Uri copyToTree(File file, String tree, ClipEncoder.Cancellation cancel) throws IOException {
        Uri treeUri = Uri.parse(tree), document = null;
        ContentResolver resolver = context.getContentResolver();
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            document = DocumentsContract.createDocument(resolver, parent, mime(file), file.getName());
            if (document == null) throw new IOException("Folder provider could not create a file");
            try (InputStream in = new FileInputStream(file); OutputStream out = resolver.openOutputStream(document, "w")) {
                if (out == null) throw new IOException("Folder provider could not open output");
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) >= 0) { if (cancel.cancelled()) throw new CancellationException(); out.write(buffer, 0, count); }
                out.flush();
            }
            // Providers need not offer atomic publication. Read back before reporting completion.
            try (InputStream in = resolver.openInputStream(document)) {
                if (in == null) throw new IOException("Cannot verify folder copy");
                byte[] buffer = new byte[32768]; long length = 0; int count;
                while ((count = in.read(buffer)) >= 0) { if (cancel.cancelled()) throw new CancellationException(); length += count; }
                if (length != file.length()) throw new IOException("Folder copy was truncated");
            }
            return document;
        } catch (Exception e) {
            if (document != null) try { DocumentsContract.deleteDocument(resolver, document); } catch (Exception ignored) {}
            if (e instanceof CancellationException) throw (CancellationException) e;
            throw new IOException("Folder copy failed; local recording kept. Reselect the folder if access was revoked.", e);
        }
    }
    static String mime(File f) {
        String n = f.getName(); return n.endsWith(".opus") ? "audio/ogg" : n.endsWith(".flac") ? "audio/flac" : n.endsWith(".m4a") ? "audio/mp4" : "audio/wav";
    }
}
