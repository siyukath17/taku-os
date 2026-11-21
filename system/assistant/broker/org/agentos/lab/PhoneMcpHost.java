package org.agentos.lab;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Device hosting for {@link PhoneMcpBridge}.
 *
 * <p>Phone tools must run inside the broker (it owns the dedicated-screen registry), but an ACP
 * agent starts MCP servers as stdio child processes. So the agent starts a tiny relay
 * ({@code app_process ... PhoneMcpHost relay}) that forwards stdio to the broker's
 * {@value #SOCKET} socket; the broker serves each connection with a bridge bound to that task.
 * Only root/system peers (the broker-spawned agent) are served.
 */
final class PhoneMcpHost {
    static final String SOCKET = "taku-phone-mcp";
    static final String ENV_TASK = "TAKU_MCP_TASK", ENV_PERMISSION = "TAKU_MCP_PERMISSION";
    private static boolean started;

    private PhoneMcpHost() {}

    /** The thread identity an ACP task uses for its background screen and Apps. */
    static String threadFor(String taskId) {
        return "acp-" + taskId;
    }

    static synchronized void start() throws Exception {
        if (started) return;
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        started = true;
        Thread listener = new Thread(() -> {
            while (true) try {
                LocalSocket socket = server.accept();
                Thread worker = new Thread(() -> serve(socket), "phone-mcp");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception stopped) {
                break;
            }
        }, "phone-mcp-listener");
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
            PhoneMcpBridge.Session session = new PhoneMcpBridge.Session(threadFor(task), hello.optString("permissionMode", "full"));
            new PhoneMcpBridge(PhoneNativeTools::definitions, PhoneMcpHost::execute, PhoneMcpBridge.modePolicy(), session)
                    .serve(in, s.getOutputStream());
        } catch (Exception closed) {
            System.err.println("phone mcp: " + closed.getClass().getSimpleName());
        }
    }

    static JSONObject execute(String tool, JSONObject arguments, PhoneMcpBridge.Session session) throws Exception {
        return PhoneNativeTools.execute(new JSONObject()
                .put("tool", tool)
                .put("arguments", arguments)
                .put("threadId", session.threadId));
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
        if (task == null || task.isEmpty()) {
            System.err.println(ENV_TASK + " is required");
            System.exit(2);
        }
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(SOCKET));
        OutputStream toBroker = socket.getOutputStream();
        toBroker.write((new JSONObject().put("task", task).put("permissionMode", permission == null ? "full" : permission) + "\n")
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
