package org.agentos.lab;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The broker's task-directory file contract (state.json, result.txt, done, ...), free of Android
 * and runtime dependencies so every {@link TaskRuntime} writes records exactly the same way.
 */
final class TaskRecords {
    private TaskRecords() {}

    /** Atomic replace: readers see the old or the new record, never a torn one. */
    static void write(File f, String t) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(t.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("atomic write failed");
        f.setReadable(true, false);
    }

    static void state(File dir, JSONObject r, String s, String m) throws Exception {
        write(
                new File(dir, "state.json"),
                new JSONObject()
                        .put("id", r.getString("id"))
                        .put("state", s)
                        .put("prompt", r.getString("prompt"))
                        .put("message", m)
                        .put("updatedAt", System.currentTimeMillis())
                        .toString());
    }
}
