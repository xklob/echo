package com.klob.echo;

import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.util.*;

/** Test-only provider. Its directory contains only generated fixtures. */
public final class FaultDocumentsProvider extends DocumentsProvider {
  private File root;
  private final Map<String, File> documents = new HashMap<>();
  private static final String[] COLUMNS = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_FLAGS,
    DocumentsContract.Document.COLUMN_SIZE
  };

  @Override
  public boolean onCreate() {
    root = new File(getContext().getCacheDir(), "fault-provider");
    root.mkdirs();
    return true;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    return new MatrixCursor(
        new String[] {
          DocumentsContract.Root.COLUMN_ROOT_ID,
          DocumentsContract.Root.COLUMN_DOCUMENT_ID,
          DocumentsContract.Root.COLUMN_TITLE,
          DocumentsContract.Root.COLUMN_FLAGS
        });
  }

  @Override
  public synchronized Cursor queryDocument(String id, String[] projection)
      throws FileNotFoundException {
    MatrixCursor c = new MatrixCursor(COLUMNS);
    File f = documents.get(id);
    if (f == null) throw new FileNotFoundException();
    c.addRow(
        new Object[] {
          id,
          f.getName(),
          "audio/wav",
          DocumentsContract.Document.FLAG_SUPPORTS_WRITE
              | DocumentsContract.Document.FLAG_SUPPORTS_DELETE,
          f.length()
        });
    return c;
  }

  @Override
  public synchronized Cursor queryChildDocuments(String parent, String[] projection, String order) {
    MatrixCursor c = new MatrixCursor(COLUMNS);
    for (Map.Entry<String, File> e : documents.entrySet())
      c.addRow(
          new Object[] {e.getKey(), e.getValue().getName(), "audio/wav", 0, e.getValue().length()});
    return c;
  }

  @Override
  public synchronized String createDocument(String parent, String mime, String name)
      throws FileNotFoundException {
    if (parent.equals("revoked")) throw new SecurityException("Simulated revoked grant");
    String id = parent + "-" + UUID.randomUUID();
    File file = new File(root, id);
    documents.put(id, file);
    return id;
  }

  @Override
  public synchronized ParcelFileDescriptor openDocument(
      String id, String mode, CancellationSignal signal) throws FileNotFoundException {
    if (id.startsWith("write-failure")) throw new FileNotFoundException("Simulated ENOSPC");
    File file = documents.get(id);
    if (file == null) throw new FileNotFoundException();
    if (mode.equals("r") && id.startsWith("truncated")) {
      file = new File(root, "empty");
      try {
        new FileOutputStream(file).close();
      } catch (IOException e) {
        throw new FileNotFoundException();
      }
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
  }

  @Override
  public boolean isChildDocument(String parent, String child) {
    return parent.equals("root") || child.startsWith(parent + "-");
  }

  @Override
  public synchronized void deleteDocument(String id) {
    File file = documents.remove(id);
    if (file != null) file.delete();
  }
}
