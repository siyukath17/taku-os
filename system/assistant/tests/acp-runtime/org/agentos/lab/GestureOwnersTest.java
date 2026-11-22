package org.agentos.lab;

import java.io.IOException;

/** Gesture owners: Codex and ACP turn ids pass the shape check; malformed or forged ids do not. */
public final class GestureOwnersTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static boolean refused(String thread, String turn) {
        try {
            GestureOwners.of(thread, turn);
            return false;
        } catch (IOException expected) {
            return "Current task and execution turn are missing".equals(expected.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        String thread = "0c9870b9-1111-4222-8333-444455556666";
        String uuid = "7d1e2f3a-aaaa-4bbb-8ccc-0123456789ab";
        check(GestureOwners.of(thread, uuid).equals(thread + "\n" + uuid), "codex turn");
        check(GestureOwners.of(thread, "acp-" + uuid).equals(thread + "\n" + "acp-" + uuid), "acp chat turn");
        check(GestureOwners.of(thread, "acp-task-1732350000123").equals(thread + "\nacp-task-1732350000123"), "acp task turn");
        // The ids AcpRuntime and AcpConversationStore really mint.
        String minted = "acp-" + java.util.UUID.randomUUID();
        check(GestureOwners.of(AcpRuntime.phoneThread("", "1001"), minted).endsWith("\n" + minted), "minted acp turn");

        check(refused("", uuid), "missing thread");
        check(refused(thread, ""), "missing turn");
        check(refused(null, uuid), "null thread");
        check(refused(thread, null), "null turn");
        check(refused("acp-" + uuid, uuid), "acp prefix is a turn, not a thread");
        check(refused(thread, "acp-"), "bare prefix");
        check(refused(thread, "acp-task-"), "empty task id");
        check(refused(thread, "acp-" + uuid.toUpperCase()), "uppercase uuid");
        check(refused(thread, "acp-" + uuid + "x"), "trailing junk");
        check(refused(thread, "acp-task-a/b"), "path in task id");
        check(refused(thread, "acp-task-" + "a".repeat(129)), "task id too long");
        check(refused(thread, uuid + "\n" + uuid), "newline would forge another owner key");
        check(refused(thread + "\n" + thread, uuid), "newline in thread");
        check(refused(thread, "turn-" + uuid), "other prefix");
        System.out.println("GestureOwnersTest: " + checks + " checks passed");
    }
}
