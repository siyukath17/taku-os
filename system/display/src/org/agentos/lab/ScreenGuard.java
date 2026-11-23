package org.agentos.lab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.ToIntFunction;

/**
 * Android-free policy of an independent screen (host-tested): which display flags it is created
 * with, and when a capture must fail instead of handing the Agent a black image (BUG-0034).
 */
final class ScreenGuard {
    private ScreenGuard() {}

    /** Mirrors {@code android.view.Display.STATE_*} (public, stable API values). */
    static final int STATE_UNKNOWN = 0, STATE_OFF = 1, STATE_ON = 2, STATE_DOZE = 3,
            STATE_DOZE_SUSPEND = 4, STATE_VR = 5, STATE_ON_SUSPEND = 6;

    static final String SCREEN_OFF = "SCREEN_OFF", SCREEN_BLACK_FRAME = "SCREEN_BLACK_FRAME";

    /** Hidden DisplayManager flags a trusted independent screen cannot work without. */
    static final String[] REQUIRED_FLAGS = {
        "VIRTUAL_DISPLAY_FLAG_TRUSTED", "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED"};
    /**
     * Best-effort flags (0 when the platform lacks them). OWN_DISPLAY_GROUP gives the screen its own
     * display/power group, so the power key putting the default group to sleep does not also turn this
     * screen STATE_OFF and leave the Agent capturing black frames.
     */
    static final String[] OPTIONAL_FLAGS = {
        "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
        "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP"};

    /** {@code base} plus every required (missing: fails) and optional (missing: skipped) flag. */
    static int virtualDisplayFlags(int base, ToIntFunction<String> flag) throws IOException {
        int flags = base;
        for (String name : REQUIRED_FLAGS) {
            int value = flag.applyAsInt(name);
            if (value == 0) throw new IOException("Required display flag unavailable: " + name);
            flags |= value;
        }
        for (String name : OPTIONAL_FLAGS) flags |= flag.applyAsInt(name);
        return flags;
    }

    /** Off, dozing or suspended displays render nothing current. UNKNOWN is not treated as off. */
    static boolean screenOn(int state) {
        return state != STATE_OFF && state != STATE_DOZE && state != STATE_DOZE_SUSPEND && state != STATE_ON_SUSPEND;
    }

    static String stateName(int state) {
        switch (state) {
            case STATE_OFF: return "OFF";
            case STATE_ON: return "ON";
            case STATE_DOZE: return "DOZE";
            case STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case STATE_VR: return "VR";
            case STATE_ON_SUSPEND: return "ON_SUSPEND";
            default: return "UNKNOWN";
        }
    }

    static void requireScreenOn(int state) throws IOException {
        if (!screenOn(state))
            throw new IOException(SCREEN_OFF + ": independent screen display state is " + stateName(state)
                    + "; no screenshot was taken");
    }

    static void requireNotBlack(boolean black) throws IOException {
        if (black)
            throw new IOException(SCREEN_BLACK_FRAME + ": every pixel of the latest rendered frame is pure black (RGB 0)");
    }

    /**
     * True only when every pixel's R, G and B bytes are exactly 0 (alpha ignored) in an RGBA_8888
     * plane. Stops at the first non-zero channel, so real content costs almost nothing; one lit
     * pixel anywhere (a dark-mode App's text, an icon, #010101 grey) means "not black". Absolute
     * reads from the current position, which is left for the caller's own copy.
     */
    static boolean isBlackRgba(ByteBuffer buffer, int width, int height, int rowStride, int pixelStride) {
        if (width <= 0 || height <= 0 || pixelStride < 3 || rowStride < width * pixelStride) return false;
        long needed = (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride + 3;
        int base = buffer.position();
        if (buffer.limit() - base < needed) return false;
        for (int y = 0; y < height; y++) {
            int row = base + y * rowStride;
            for (int x = 0; x < width; x++) {
                int at = row + x * pixelStride;
                if (buffer.get(at) != 0 || buffer.get(at + 1) != 0 || buffer.get(at + 2) != 0) return false;
            }
        }
        return true;
    }
}
