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
        System.out.println("BrokerLogTest passed " + checks + " checks");
    }
}
