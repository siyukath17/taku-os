package org.agentos.lab;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The broker's stderr log: timestamped lines, one rotation to {@code .1}, bounded total size. */
public final class BrokerLogTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("broker-log").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "broker.log"), older = new File(dir, "broker.log.1");
        PrintStream err = new PrintStream(new BrokerLog(file, 4096), true, "UTF-8");
        err.println("acp prewarm (thread/read) ready in 612ms");
        err.print("partial ");
        err.println("line");
        String first = read(file);
        check(first.matches("(?s)\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} acp prewarm \\(thread/read\\) ready in 612ms\n"
                + "\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} partial line\n"), "each line is timestamped once: " + first);

        String line = "x".repeat(200);
        for (int i = 0; i < 200; i++) err.println(i + " " + line);
        check(older.isFile() && file.isFile(), "past the cap the log rotates to broker.log.1");
        check(file.length() <= 4096 + 300 && older.length() <= 4096 + 300,
                "each file stays near the cap: " + file.length() + " / " + older.length());
        check(!new File(dir, "broker.log.2").exists() && dir.list().length == 2, "only one older file is kept");
        check(read(file).contains("199 " + line), "the newest line is in broker.log");
        check(!read(older).contains("0 " + line + "\n") || read(older).contains(" " + line), "older lines move to broker.log.1");

        // A vanished directory never throws into the caller; the next write reopens the file.
        for (File f : dir.listFiles()) f.delete();
        err.println("after cleanup");
        err.println("again");
        check(file.isFile() && read(file).contains("again"), "the log reopens after its file disappeared");

        tornLineFromAKilledBroker();
        rotationKeepsEveryTurnLineWhole();
        String sample = System.getenv("ACP_TTFT_SAMPLE_DIR");
        if (sample != null && !sample.isEmpty()) writeTtftSample(new File(sample));
        System.out.println("BrokerLogTest passed " + checks + " checks");
    }

    private static final java.util.regex.Pattern STAMPED = java.util.regex.Pattern.compile("\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} .*");

    /** A broker killed mid-line left no final newline: the next broker's first line still starts a line of its own. */
    static void tornLineFromAKilledBroker() throws Exception {
        File dir = Files.createTempDirectory("broker-log-torn").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "broker.log");
        Files.write(file.toPath(), "11-23 09:59:59.000 acp turn 41 comple".getBytes(StandardCharsets.UTF_8));
        PrintStream err = new PrintStream(new BrokerLog(file, 4096), true, "UTF-8");
        err.println("broker start pid 4242");
        String[] lines = read(file).split("\n", -1);
        check(lines.length == 3 && lines[0].endsWith("comple") && lines[2].isEmpty(), "the torn line is closed, not extended: " + read(file));
        check(STAMPED.matcher(lines[1]).matches() && lines[1].endsWith(" broker start pid 4242"),
                "the new broker's header is a stamped line of its own: " + lines[1]);
        err.println("next");
        check(read(file).split("\n").length == 3, "no extra blank lines once the file ends with a newline");
    }

    /**
     * Rotation happens only between lines: across broker.log.1 + broker.log every turn line written
     * after the last dropped file is whole (stamped, ends in "(ms)") and in order, non-ASCII included,
     * also with several writer threads.
     */
    static void rotationKeepsEveryTurnLineWhole() throws Exception {
        File dir = Files.createTempDirectory("broker-log-turns").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "broker.log"), older = new File(dir, "broker.log.1");
        int threads = 4, perThread = 60;
        String failure = "acp prewarm (onResume) failed: java.io.IOException: 连接被重置 ✓";
        long total = 0;
        for (int t = 0; t < threads; t++)
            for (int i = 0; i < perThread; i++)
                total += 2 * 19 + 2 + turnLine(t, i).getBytes(StandardCharsets.UTF_8).length + failure.getBytes(StandardCharsets.UTF_8).length;
        // A cap of 60% of the output: exactly one rotation, so nothing may be missing.
        PrintStream err = new PrintStream(new BrokerLog(file, total * 6 / 10), true, "UTF-8");
        Thread[] writers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int id = t;
            writers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    err.println(turnLine(id, i));
                    err.println(failure);
                }
            });
            writers[t].start();
        }
        for (Thread writer : writers) writer.join();
        String both = read(older) + read(file);
        String[] lines = both.split("\n");
        int turns = 0;
        java.util.Map<Integer, Integer> lastByThread = new java.util.HashMap<>();
        for (String line : lines) {
            check(STAMPED.matcher(line).matches(), "every line is stamped (none split by rotation): " + line);
            if (line.contains(" acp turn ")) {
                check(line.endsWith(" (ms)"), "a turn line is whole: " + line);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(" acp turn t(\\d)-(\\d+) ").matcher(line);
                check(m.find(), "turn id: " + line);
                int thread = Integer.parseInt(m.group(1)), index = Integer.parseInt(m.group(2));
                Integer last = lastByThread.put(thread, index);
                check(last == null ? index == 0 : index == last + 1, "turn lines of one writer are contiguous across the rotation: " + line);
                turns++;
            } else check(line.endsWith("连接被重置 ✓"), "non-ASCII text survives rotation intact: " + line);
        }
        check(turns > 0 && both.endsWith("\n") && read(older).endsWith("\n"), "both files end on a line boundary");
        for (int t = 0; t < threads; t++)
            check(lastByThread.getOrDefault(t, -1) == perThread - 1, "every turn line of every writer is kept: " + lastByThread);
        check(turns == threads * perThread && older.length() > 0, "one rotation lost no turn line: " + turns);
    }

    private static String turnLine(int writer, int index) {
        AcpRuntime.Timings timings = new AcpRuntime.Timings();
        timings.poolState = AcpRuntime.Timings.WARM_HIT;
        timings.turnTotalMs = index;
        return timings.summary("t" + writer + "-" + index, "completed");
    }

    /**
     * Real broker.log output for scripts/test-acp-ttft-report.py (ACP_TTFT_SAMPLE_DIR): the turn lines
     * come from {@link AcpRuntime.Timings#summary}, the files from {@link BrokerLog} with a rotation in
     * between, and expected.json says what the report must find.
     */
    static void writeTtftSample(File dir) throws Exception {
        dir.mkdirs();
        File file = new File(dir, "broker.log"), older = new File(dir, "broker.log.1");
        file.delete();
        older.delete();
        // One rotation for this much output: broker.log.1 holds the start, broker.log the rest.
        PrintStream err = new PrintStream(new BrokerLog(file, 2048), true, "UTF-8");
        org.json.JSONArray turns = new org.json.JSONArray();
        err.println("broker start pid 7001");
        err.println("acp prewarm (startup) ready in 5924ms");
        for (int i = 0; i < 12; i++) {
            AcpRuntime.Timings t = new AcpRuntime.Timings();
            String pool = i == 0 ? AcpRuntime.Timings.COLD_START : i % 4 == 3 ? AcpRuntime.Timings.WAITED_PREWARM : AcpRuntime.Timings.WARM_HIT;
            t.poolState = pool;
            t.warmWaitMs = i;
            if (i == 0) t.initializeMs = 480; else t.pingMs = 2;
            t.claimDelayMs = 10 + i;
            t.sessionPrepareMs = 300 + i;
            t.configOptionMs = i % 2 == 0 ? 12 : -1;
            t.totalPrepMs = 400 + i;
            t.promptToFirstChunkMs = 2000 + i;
            t.promptToFirstTextMs = i == 5 ? -1 : 3000 + i; // a turn that only called tools
            t.promptToFirstToolMs = i % 3 == 0 ? 2500 + i : -1;
            t.turnTotalMs = 5000 + i;
            String status = i == 7 ? "failed" : "completed";
            String id = "17" + String.format(java.util.Locale.ROOT, "%02d", i);
            err.println(t.summary(id, status));
            err.println("acp tool label: 打开设置 → Wi‑Fi ✓ (" + id + ")");
            turns.put(new org.json.JSONObject().put("id", id).put("status", status).put("poolState", pool)
                    .put("warmWaitMs", i).put("promptToFirstTextMs", i == 5 ? org.json.JSONObject.NULL : 3000 + i)
                    .put("configOptionMs", i % 2 == 0 ? 12 : org.json.JSONObject.NULL).put("turnTotalMs", 5000 + i));
            if (i == 6) {
                err.println("broker start pid 7002");
                err.println("acp prewarm (onResume) skipped: pool-busy");
            }
        }
        err.println("acp prewarm (overlay) failed: java.io.IOException: boom");
        check(older.isFile() && file.isFile(), "the sample spans a rotation");
        Files.write(new File(dir, "expected.json").toPath(), new org.json.JSONObject().put("turns", turns)
                .put("starts", new org.json.JSONArray().put(7001).put(7002)).put("sinceStartFirstTurn", "1707")
                .toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
