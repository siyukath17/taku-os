package org.agentos.lab;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Device hosting for {@link PhoneMcpBridge}.
 *
 * <p>Phone tools must run inside the broker (it owns the dedicated-screen registry), but an ACP
 * agent starts MCP servers as stdio child processes. So the agent starts a tiny relay that forwards
 * stdio to the broker, which serves each connection with a bridge bound to that conversation:
 * <ul>
 *   <li>native (default): {@code sh -c '{ printf hello; exec cat; } | toybox nc -U'} to the root-only
 *       filesystem socket {@link AcpRuntime#PHONE_MCP_SOCKET} — a few ms, no JVM;</li>
 *   <li>fallback: {@code app_process ... PhoneMcpHost relay} to the abstract {@value #SOCKET} socket
 *       (~750ms of JVM start), used when the filesystem socket is unavailable.</li>
 * </ul>
 * Only root/system peers (the broker-spawned agent) are served.
 *
 * <p>A connection is not a turn: a warm agent keeps its MCP server across turns. A call's turn is the
 * thread's running turn in {@link AcpPhoneTurns}; {@link AcpRuntime} starts and ends turns there
 * (gesture owner, background screen release) explicitly.
 */
final class PhoneMcpHost {
    static final String SOCKET = "taku-phone-mcp";
    static final String ENV_TASK = "TAKU_MCP_TASK", ENV_PERMISSION = "TAKU_MCP_PERMISSION",
            ENV_THREAD = "TAKU_MCP_THREAD", ENV_TURN = "TAKU_MCP_TURN";
    private static final String THREAD_ID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static boolean started;
    /** Keeps the bound filesystem socket's descriptor reachable for the broker's lifetime. */
    private static LocalSocket filesystemSocket;

    private PhoneMcpHost() {}

    /**
     * The thread identity an ACP task uses for its background screen and Apps when the relay names
     * none: the screen registries accept only native thread UUIDs (INVALID_SCREEN_THREAD otherwise).
     */
    static String threadFor(String taskId) {
        return AcpRuntime.phoneThread("", taskId);
    }

    static String threadFor(String taskId, String requested) {
        return requested != null && requested.matches(THREAD_ID) ? requested : threadFor(taskId);
    }

    static synchronized void start() throws Exception {
        if (started) return;
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        started = true;
        listen(server, "phone-mcp-listener");
        // The native relay's socket. Without it, AcpRuntime declares the Java relay instead.
        try {
            listen(bindFilesystem(new File(AcpRuntime.PHONE_MCP_SOCKET)), "phone-mcp-fs-listener");
        } catch (Exception unavailable) {
            System.err.println("phone mcp native socket unavailable: " + unavailable.getMessage());
        }
    }

    /** A root-only (0600, in a 0700 directory) filesystem socket, replacing a stale one. */
    private static LocalServerSocket bindFilesystem(File path) throws Exception {
        File dir = path.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
        Os.chmod(dir.getPath(), 0700);
        if (path.exists() && !path.delete()) throw new java.io.IOException("cannot replace " + path);
        LocalSocket bound = new LocalSocket(LocalSocket.SOCKET_STREAM);
        try {
            bound.bind(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            Os.chmod(path.getPath(), 0600);
            LocalServerSocket server = new LocalServerSocket(bound.getFileDescriptor());
            filesystemSocket = bound;
            return server;
        } catch (Exception failed) {
            try { bound.close(); } catch (Exception ignored) {}
            path.delete();
            throw failed;
        }
    }

    private static void listen(LocalServerSocket server, String name) {
        Thread listener = new Thread(() -> {
            while (true) {
                LocalSocket socket;
                try {
                    socket = server.accept();
                } catch (Throwable stopped) {
                    break;
                }
                try {
                    Thread worker = new Thread(() -> serve(socket), "phone-mcp");
                    worker.setDaemon(true);
                    worker.start();
                } catch (Throwable noThread) {
                    // Never an uncaught throwable on a broker thread (it ends app_process): drop this client.
                    System.err.println("phone mcp: " + noThread.getClass().getSimpleName());
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }, name);
        listener.setDaemon(true);
        listener.start();
    }

    static void serve(LocalSocket socket) {
        try (LocalSocket s = socket) {
            int uid = s.getPeerCredentials().getUid();
            if (uid != 0 && uid != 1000) return;
            InputStream in = s.getInputStream();
            JSONObject hello = new JSONObject(readLine(in, 4096));
            String task = hello.getString("task");
            if (!task.matches("[A-Za-z0-9._-]{1,128}")) return;
            String turn = hello.optString("turn", "");
            if (!turn.matches("[A-Za-z0-9._-]{1,160}")) turn = "acp-task-" + task;
            String thread = threadFor(task, hello.optString("thread", ""));
            // A warm agent keeps this connection across turns: each call runs in the thread's current
            // turn. AcpRuntime starts and ends turns (gestures, background screen) itself; closing the
            // connection releases nothing, or a warm process would leak or steal a turn's screen.
            PhoneMcpBridge.Session session = new PhoneMcpBridge.Session(thread,
                    hello.optString("permissionMode", "full"), turn, () -> AcpPhoneTurns.current(thread));
            AcpConversations.phoneSessionStarted(session.threadId);
            new PhoneMcpBridge(PhoneNativeTools::definitions, PhoneMcpHost::execute, PhoneMcpBridge.modePolicy(), session)
                    .serve(in, s.getOutputStream());
        } catch (Throwable closed) {
            // Throwable, not Exception: a worker thread's uncaught Error would end the broker's app_process.
            System.err.println("phone mcp: " + closed.getClass().getSimpleName());
        }
    }

    /**
     * Device side of {@link AcpPhoneTurns}: an ACP turn owns its gesture pointer from start to end and
     * its background screen is released when it ends, as NativeBridge does on turn/completed.
     */
    static final AcpPhoneTurns.Hooks TURN_HOOKS = new AcpPhoneTurns.Hooks() {
        @Override public void started(String thread, String turn) {
            AcpConversations.phoneSessionStarted(thread);
            PhoneGestures.startTurn(thread, turn);
        }

        @Override public void ended(String thread, String turn, boolean current) throws Exception {
            PhoneGestures.endTurn(thread, turn);
            // The screen is the thread's: a stale or repeated end must not release a newer turn's.
            if (current) DedicatedScreensOnDevice.shared().turnEnded(thread);
        }
    };

    static JSONObject execute(String tool, JSONObject arguments, PhoneMcpBridge.Session session) throws Exception {
        return PhoneNativeTools.execute(new JSONObject()
                .put("tool", tool)
                .put("arguments", arguments)
                .put("threadId", session.threadId)
                .put("turnId", session.turnId()));
    }

    private static String readLine(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            line.write(c);
            if (line.size() > limit) throw new IllegalStateException("handshake too long");
        }
        return line.toString("UTF-8");
    }

    /** Relay process entry point: {@code app_process /system/bin org.agentos.lab.PhoneMcpHost relay}. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"relay".equals(args[0])) {
            System.err.println("usage: PhoneMcpHost relay");
            System.exit(2);
        }
        String task = System.getenv(ENV_TASK);
        String permission = System.getenv(ENV_PERMISSION);
        String thread = System.getenv(ENV_THREAD), turn = System.getenv(ENV_TURN);
        if (task == null || task.isEmpty()) {
            System.err.println(ENV_TASK + " is required");
            System.exit(2);
        }
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(SOCKET));
        OutputStream toBroker = socket.getOutputStream();
        toBroker.write((new JSONObject().put("task", task).put("permissionMode", permission == null ? "full" : permission)
                .put("thread", thread == null ? "" : thread).put("turn", turn == null ? "" : turn) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        toBroker.flush();
        Thread upstream = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = System.in.read(buffer)) != -1) {
                    toBroker.write(buffer, 0, n);
                    toBroker.flush();
                }
                socket.shutdownOutput();
            } catch (Exception closed) {
                // Broker side closed; the downstream copy ends too.
            }
        }, "phone-mcp-up");
        upstream.setDaemon(true);
        upstream.start();
        try (InputStream fromBroker = socket.getInputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fromBroker.read(buffer)) != -1) {
                System.out.write(buffer, 0, n);
                System.out.flush();
            }
        }
        System.exit(0);
    }
}
