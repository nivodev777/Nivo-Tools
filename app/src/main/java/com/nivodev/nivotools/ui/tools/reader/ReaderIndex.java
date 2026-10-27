package com.nivodev.nivotools.ui.tools.reader;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Device file index for the Reader list, the same spirit as the music scan.
 * Android 12 and below: MediaStore.Files (needs READ_EXTERNAL_STORAGE).
 * Android 13+: arbitrary docs cannot be listed, so the user grants one or
 * more folders once (OpenDocumentTree) - those grants persist and the index
 * walks each granted folder to collect documents.
 */
public final class ReaderIndex {
    private ReaderIndex() {
    }

    /** One scannable document row. */
    public static class Hit {
        public final Uri uri;
        public final String name;

        Hit(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }

        public boolean isPdf() {
            return name != null && name.toLowerCase().endsWith(".pdf");
        }
    }

    private static final String[] MEDIA_PROJ = {"_id", "_display_name", "mime_type"};
    private static final String[] DOC_PROJ = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE};

    /** Files from every previously granted folder (all API levels). */
    public static List<Hit> treeFiles(Context c) {
        List<Hit> out = new ArrayList<>();
        try {
            SharedPreferences p = prefs(c);
            JSONArray arr = new JSONArray(p.getString("trees", "[]"));
            boolean dirty = false;
            for (int i = 0; i < arr.length() && out.size() < 400; i++) {
                try {
                    Uri tree = Uri.parse(arr.getJSONObject(i).optString("u"));
                    int before = out.size();
                    out.addAll(walkTree(c, tree));
                    if (out.size() == before && !stillGranted(c, tree)) {
                        // nothing listed AND the persistable grant is gone
                        // (revoked or source removed) - drop the dead entry
                        arr.remove(i);
                        dirty = true;
                        i--;
                    }
                } catch (Throwable t) {
                    arr.remove(i);
                    dirty = true;
                    i--;
                }
            }
            if (dirty) {
                p.edit().putString("trees", arr.toString()).apply();
            }
        } catch (Throwable t) {
        }
        return out;
    }

    /** MediaStore docs; caller must hold READ_EXTERNAL_STORAGE (<= API 32). */
    public static List<Hit> mediaFiles(Context c) {
        List<Hit> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT > 32) {
            return out;
        }
        try {
            Cursor cur = c.getContentResolver().query(
                    MediaStore.Files.getContentUri("external"), MEDIA_PROJ, null,
                    null, "date_modified DESC");
            if (cur == null) {
                return out;
            }
            Set<String> seen = new HashSet<>();
            int idIdx = cur.getColumnIndexOrThrow("_id");
            int nameIdx = cur.getColumnIndex(MEDIA_PROJ[1]);
            int mimeIdx = cur.getColumnIndex(MEDIA_PROJ[2]);
            while (cur.moveToNext() && out.size() < 400) {
                String name = cur.getString(nameIdx);
                String mime = cur.getString(mimeIdx);
                if (!isReadableDoc(name, mime)) {
                    continue;
                }
                if (seen.add(name)) {
                    out.add(new Hit(ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri("external"),
                            cur.getLong(idIdx)), name));
                }
            }
            cur.close();
        } catch (Throwable t) {
        }
        return out;
    }

    /** Grants + remembers one folder (persistable SAF grant). */
    public static void addTree(Context c, Uri treeUri) {
        // ONLY READ is a persistable flag: the PREFIX flag is NOT, so
        // including it made takePersistableUriPermission throw and the
        // grant was never saved -> the picker appeared again on every
        // app open. Read-only is all the listing/opening needs.
        try {
            c.getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable t) {
        }
        // persisted grants have a system cap (~128) - release the oldest
        // ones first so a fresh folder pick can never throw "too many
        // persisted URI permissions"
        try {
            java.util.List<android.content.UriPermission> held =
                    c.getContentResolver().getPersistedUriPermissions();
            for (int i = 0; i < held.size() - 63; i++) {
                try {
                    c.getContentResolver().releasePersistableUriPermission(
                            held.get(i).getUri(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
        }
        try {
            SharedPreferences p = prefs(c);
            JSONArray arr = new JSONArray(p.getString("trees", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                if (treeUri.toString().equals(
                        arr.getJSONObject(i).optString("u"))) {
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("u", treeUri.toString());
            arr.put(o);
            p.edit().putString("trees", arr.toString()).apply();
        } catch (Throwable t) {
        }
    }

    /** is this tree still among the persistable read grants? */
    private static boolean stillGranted(Context c, Uri treeUri) {
        try {
            for (android.content.UriPermission perm :
                    c.getContentResolver().getPersistedUriPermissions()) {
                if (treeUri.equals(perm.getUri()) && perm.isReadPermission()) {
                    return true;
                }
            }
        } catch (Throwable t) {
        }
        return false;
    }

    /** Depth-1 walk of a granted folder via DocumentsContract. */
    private static List<Hit> walkTree(Context c, Uri tree) {
        List<Hit> out = new ArrayList<>();
        try {
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            Cursor cur = c.getContentResolver().query(kids, DOC_PROJ, null, null, null);
            if (cur == null) {
                return out;
            }
            int idIdx = cur.getColumnIndexOrThrow(DOC_PROJ[0]);
            int nameIdx = cur.getColumnIndex(DOC_PROJ[1]);
            int mimeIdx = cur.getColumnIndex(DOC_PROJ[2]);
            Set<String> seen = new HashSet<>();
            while (cur.moveToNext() && out.size() < 400) {
                String name = cur.getString(nameIdx);
                String mime = cur.getString(mimeIdx);
                if (name == null || !isReadableDoc(name, mime)) {
                    continue;
                }
                if (name.endsWith("/") || mimeIsFolder(mime)) {
                    continue;
                }
                if (seen.add(name)) {
                    out.add(new Hit(DocumentsContract
                            .buildDocumentUriUsingTree(tree, cur.getString(idIdx)),
                            name));
                }
            }
            cur.close();
        } catch (Throwable t) {
        }
        return out;
    }

    private static boolean mimeIsFolder(String mime) {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
    }

    private static boolean isReadableDoc(String name, String mime) {
        if (name == null) {
            return false;
        }
        String low = name.toLowerCase();
        if (low.endsWith(".pdf") || low.endsWith(".txt") || low.endsWith(".md")
                || low.endsWith(".doc") || low.endsWith(".docx")
                || low.endsWith(".odt") || low.endsWith(".csv")
                || low.endsWith(".log") || low.endsWith(".html")
                || low.endsWith(".xml") || low.endsWith(".json")) {
            return true;
        }
        return mime != null && (mime.contains("pdf") || mime.startsWith("text/")
                || mime.contains("msword") || mime.contains("officedocument"));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("reader_store", Context.MODE_PRIVATE);
    }
}
