package org.agentos.lab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * BUG-0034: after the power key the independent screen went STATE_OFF and the Agent kept receiving
 * black screenshots, concluded the page was empty and gave up. Covers both layers: the screen is
 * created in its own display group (root cause), and an off screen or a pure-black frame becomes a
 * clear retryable SCREEN_OFF / SCREEN_BLACK_FRAME error instead of an image (fallback) — without
 * ever flagging a dark-mode App.
 */
public final class ScreenOffCaptureTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    interface Checked { void run() throws Exception; }

    private static IOException rejects(String code, Checked action) throws Exception {
        try {
            action.run();
        } catch (IOException error) {
            check(error.getMessage() != null && error.getMessage().startsWith(code + ":"), "expected " + code + ", got " + error);
            return error;
        }
        throw new AssertionError("expected " + code);
    }

    static final int OWN_DISPLAY_GROUP = 1 << 11;

    /** The leading {@code CODE:} that tool-error consumers (e.g. the phone MCP bridge) key on. */
    static String code(String message) {
        Matcher m = Pattern.compile("^([A-Z][A-Z0-9_]{2,63})(?::|$)").matcher(message);
        return m.find() ? m.group(1) : null;
    }

    static Map<String, Integer> platformFlags() {
        Map<String, Integer> flags = new HashMap<>();
        flags.put("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1 << 10);
        flags.put("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", 1 << 14);
        flags.put("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED", 1 << 16);
        flags.put("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", 1 << 8);
        flags.put("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", 1 << 6);
        flags.put("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", OWN_DISPLAY_GROUP);
        return flags;
    }

    /** Root cause: the screen owns its display (and power) group, so the power key cannot switch it off. */
    static void displayFlags() throws Exception {
        Map<String, Integer> platform = platformFlags();
        int base = 1 | 8; // PUBLIC | OWN_CONTENT_ONLY
        int flags = ScreenGuard.virtualDisplayFlags(base, name -> platform.getOrDefault(name, 0));
        check((flags & OWN_DISPLAY_GROUP) != 0, "the independent screen is created with VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP");
        check((flags & base) == base, "PUBLIC | OWN_CONTENT_ONLY are kept");
        for (int value : platform.values()) check((flags & value) == value, "every available flag is composed");
        // A platform lacking the optional group flag still creates the screen (reflection returns 0).
        platform.remove("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP");
        int older = ScreenGuard.virtualDisplayFlags(base, name -> platform.getOrDefault(name, 0));
        check((older & OWN_DISPLAY_GROUP) == 0 && (older & (1 << 10)) != 0, "missing optional flag is skipped, not fatal");
        // A missing required flag still fails loudly, as before.
        platform.remove("VIRTUAL_DISPLAY_FLAG_TRUSTED");
        try {
            ScreenGuard.virtualDisplayFlags(base, name -> platform.getOrDefault(name, 0));
            throw new AssertionError("missing TRUSTED must fail");
        } catch (IOException expected) {
            check(expected.getMessage().contains("VIRTUAL_DISPLAY_FLAG_TRUSTED"), "required flag failure names the flag");
        }
    }

    /** The host's capture refuses every non-lit state; ON / VR / UNKNOWN still capture. */
    static void displayState() throws Exception {
        for (int state : new int[]{ScreenGuard.STATE_OFF, ScreenGuard.STATE_DOZE, ScreenGuard.STATE_DOZE_SUSPEND, ScreenGuard.STATE_ON_SUSPEND}) {
            IOException error = rejects("SCREEN_OFF", () -> ScreenGuard.requireScreenOn(state));
            check(error.getMessage().contains(ScreenGuard.stateName(state)), "SCREEN_OFF names the state " + state);
        }
        IOException off = rejects("SCREEN_OFF", () -> ScreenGuard.requireScreenOn(1)); // android.view.Display.STATE_OFF
        check(off.getMessage().contains("OFF"), "STATE_OFF (1) is SCREEN_OFF");
        ScreenGuard.requireScreenOn(ScreenGuard.STATE_ON);
        ScreenGuard.requireScreenOn(ScreenGuard.STATE_VR);
        ScreenGuard.requireScreenOn(ScreenGuard.STATE_UNKNOWN);
        check(ScreenGuard.screenOn(2) && !ScreenGuard.screenOn(1) && !ScreenGuard.screenOn(3), "Display.STATE_* values match Android's");
        ScreenGuard.requireNotBlack(false);
        rejects("SCREEN_BLACK_FRAME", () -> ScreenGuard.requireNotBlack(true));
    }

    static final int W = 720, H = 1280;

    /** An RGBA_8888 plane filled with one colour, alpha 255, with optional row padding bytes. */
    static ByteBuffer plane(int padPixels, int r, int g, int b, int padByte) {
        int rowStride = (W + padPixels) * 4;
        ByteBuffer buffer = ByteBuffer.allocate(rowStride * H);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W + padPixels; x++) {
                int at = y * rowStride + x * 4;
                boolean pad = x >= W;
                buffer.put(at, (byte) (pad ? padByte : r)).put(at + 1, (byte) (pad ? padByte : g))
                      .put(at + 2, (byte) (pad ? padByte : b)).put(at + 3, (byte) 0xff);
            }
        return buffer;
    }

    static void set(ByteBuffer buffer, int padPixels, int x, int y, int r, int g, int b) {
        int at = y * (W + padPixels) * 4 + x * 4;
        buffer.put(at, (byte) r).put(at + 1, (byte) g).put(at + 2, (byte) b);
    }

    static boolean black(ByteBuffer buffer, int padPixels) {
        return ScreenGuard.isBlackRgba(buffer, W, H, (W + padPixels) * 4, 4);
    }

    /** Only a frame whose every pixel is RGB 0 is black; any dark-mode content is not. */
    static void blackFrames() {
        check(black(plane(0, 0, 0, 0, 0), 0), "a pure-black opaque frame is black");
        check(black(plane(16, 0, 0, 0, 0x7f), 16), "row padding is never read as content");
        ByteBuffer transparent = plane(0, 0, 0, 0, 0);
        for (int i = 3; i < transparent.capacity(); i += 4) transparent.put(i, (byte) 0);
        check(black(transparent, 0), "alpha is ignored");

        check(!black(plane(0, 0x12, 0x12, 0x12, 0), 0), "Material dark surface #121212 is not black");
        check(!black(plane(0, 0x1e, 0x1e, 0x1e, 0), 0), "dark grey #1E1E1E is not black");
        check(!black(plane(0, 1, 1, 1, 0), 0), "near-black #010101 is not black");
        ByteBuffer amoled = plane(0, 0, 0, 0, 0); // OLED-black dark theme with one line of white text
        for (int x = 300; x < 420; x++) set(amoled, 0, x, 640, 0xff, 0xff, 0xff);
        check(!black(amoled, 0), "AMOLED-black dark mode with a little text is not black");
        ByteBuffer lastPixel = plane(16, 0, 0, 0, 0);
        set(lastPixel, 16, W - 1, H - 1, 0, 0, 1);
        check(!black(lastPixel, 16), "one dim pixel in the last corner (blue channel 1) is not black");
        ByteBuffer firstRed = plane(0, 0, 0, 0, 0);
        set(firstRed, 0, 0, 0, 1, 0, 0);
        check(!black(firstRed, 0), "one red-channel pixel is not black");

        ByteBuffer offset = ByteBuffer.allocate(W * H * 4 + 64);
        offset.position(64);
        check(ScreenGuard.isBlackRgba(offset, W, H, W * 4, 4), "reads start at the buffer's position");
        check(offset.position() == 64, "the caller's buffer position is left alone");
        offset.put(64, (byte) 9);
        check(!ScreenGuard.isBlackRgba(offset, W, H, W * 4, 4), "content at the position is seen");
        check(!ScreenGuard.isBlackRgba(ByteBuffer.allocate(16), W, H, W * 4, 4), "a short buffer is never called black");
        check(!ScreenGuard.isBlackRgba(ByteBuffer.allocate(W * H * 4), W, H, W * 2, 4), "an impossible stride is never called black");
    }

    /** The broker turns a host refusal into one clear, coded, retryable Agent error. */
    static void brokerErrors() throws Exception {
        String hostOff = "command exit=1: phone-display: error: SCREEN_OFF: independent screen display state is OFF; no screenshot was taken\n";
        IOException off = PhoneExecutionScreens.captureFailure(new IOException(hostOff));
        check(off.getMessage().equals(PhoneExecutionScreens.SCREEN_OFF_MESSAGE), "host SCREEN_OFF becomes the Agent message");
        check("SCREEN_OFF".equals(code(off.getMessage())), "the error code is SCREEN_OFF");
        check(off.getMessage().contains("not blank") && off.getMessage().contains("Retry observe_background"),
                "SCREEN_OFF says the page is not blank and to retry");
        check(off.getMessage().length() <= 500, "fits the MCP error text clip");
        check(off.getCause() != null && off.getCause().getMessage().equals(hostOff), "the host detail is kept as the cause");

        IOException black = PhoneExecutionScreens.captureFailure(new IOException("command exit=1: phone-display: error: SCREEN_BLACK_FRAME: every pixel ..."));
        check("SCREEN_BLACK_FRAME".equals(code(black.getMessage())), "black frame code");
        check(black.getMessage().contains("retry observe_background") && black.getMessage().contains("open_app"),
                "black frame is retryable and points an empty screen at open_app, never an endless retry");
        check(black.getMessage().length() <= 500, "black frame fits the MCP error text clip");

        IOException other = new IOException("command exit=1: phone-display: Host is unavailable");
        check(PhoneExecutionScreens.captureFailure(other) == other, "unrelated failures pass through unchanged");
        IOException none = new IOException((String) null);
        check(PhoneExecutionScreens.captureFailure(none) == none, "a message-less failure passes through");
    }

    /** validateCapture: identity first, then a capture metadata still saying the screen is off. */
    static void validateCapture() throws Exception {
        PhoneExecutionScreens.Screen screen = new PhoneExecutionScreens.Screen("maps", "gen-1", 7, false);
        JSONObject frame = new JSONObject().put("displayId", 7).put("screenKey", "maps").put("generation", "gen-1");
        screen.validateCapture(frame); // older hosts: no displayState
        screen.validateCapture(new JSONObject(frame.toString()).put("displayState", "ON"));
        screen.validateCapture(new JSONObject(frame.toString()).put("displayState", "UNKNOWN"));
        for (String state : new String[]{"OFF", "DOZE", "DOZE_SUSPEND", "ON_SUSPEND"}) {
            IOException error = rejects("SCREEN_OFF", () -> screen.validateCapture(new JSONObject(frame.toString()).put("displayState", state)));
            check(error.getMessage().equals(PhoneExecutionScreens.SCREEN_OFF_MESSAGE), "validateCapture " + state + " is the Agent SCREEN_OFF message");
        }
        try {
            screen.validateCapture(new JSONObject(frame.toString()).put("generation", "gen-2").put("displayState", "OFF"));
            throw new AssertionError("a replaced screen must fail");
        } catch (IOException replaced) {
            check("SCREEN_GENERATION_CHANGED".equals(replaced.getMessage()), "identity is checked before display state");
        }
    }

    /** The device host is wired to the guard (it needs Android and cannot run on the host). */
    static void hostWiring() throws Exception {
        String host = Files.readString(Path.of("system/display/src/org/agentos/lab/VirtualDisplayHost.java"));
        check(host.contains("ScreenGuard.virtualDisplayFlags("), "VirtualDisplayHost composes its flags through ScreenGuard");
        check(host.contains("ScreenGuard.requireScreenOn(") && host.contains("display.getState()"), "capture checks the display state");
        check(host.contains("ScreenGuard.isBlackRgba(") && host.contains("ScreenGuard.requireNotBlack("), "capture checks for a black frame");
        String tools = Files.readString(Path.of("system/assistant/broker/org/agentos/lab/PhoneNativeTools.java"));
        check(tools.contains("PhoneExecutionScreens.captureFailure("), "observe_background maps capture refusals");
    }

    public static void main(String[] args) throws Exception {
        displayFlags();
        displayState();
        blackFrames();
        brokerErrors();
        validateCapture();
        hostWiring();
        System.out.println("ScreenOffCaptureTest: " + checks + " checks passed");
    }
}
