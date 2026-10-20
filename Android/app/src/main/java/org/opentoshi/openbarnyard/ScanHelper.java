package org.opentoshi.openbarnyard;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.opentoshi.openbarnyard.core.GameDataScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Bridges Android storage (plain files under the app folder, or a SAF tree
 * the user picked) to the pure {@link GameDataScanner}. Always call from a
 * background thread.
 */
public final class ScanHelper {
    private static final String TAG = "OpenBarnyard";
    private static final int MAX_FILES = 20000;

    private ScanHelper() {}

    public static GameDataScanner.Result scan(Context ctx) {
        String uri = Storage.gameDataUri(ctx);
        List<String> paths;
        if (uri != null && uri.length() > 0) {
            paths = walkSafTree(ctx, Uri.parse(uri));
        } else {
            String path = Storage.gameDataPathForNative(ctx);
            paths = Storage.walkFiles(new File(path));
        }
        return GameDataScanner.analyse(paths);
    }

    static List<String> walkSafTree(Context ctx, Uri treeUri) {
        List<String> out = new ArrayList<String>();
        ContentResolver resolver = ctx.getContentResolver();
        Queue<String[]> queue = new LinkedList<String[]>();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            queue.add(new String[]{rootId, ""});
            String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            while (!queue.isEmpty() && (out.size() < MAX_FILES)) {
                String[] item = queue.poll();
                String docId = item[0];
                String prefix = item[1];
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
                Cursor cursor = null;
                try {
                    cursor = resolver.query(children, projection, null, null, null);
                    if (cursor == null) continue;
                    while (cursor.moveToNext()) {
                        if (out.size() >= MAX_FILES) break;
                        String childId = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        if (name == null) continue;
                        String rel = prefix.length() == 0 ? name : prefix + "/" + name;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                            queue.add(new String[]{childId, rel});
                        } else {
                            out.add(rel);
                        }
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SAF walk failed", e);
        }
        return out;
    }
}
