package org.agentos.assistant;

import org.agentos.lab.AcpChatFixtures;
import org.json.JSONObject;

/** The chat page's own parser (AgentConversation) renders an ACP turn like a native one. */
public final class AcpChatProjectionTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        JSONObject done = AcpChatFixtures.projectedConversation(false);
        AgentConversation conversation = AgentConversation.read(done);
        check(!conversation.running, "a finished ACP turn is not running");
        check("hi".equals(conversation.title), "title from the first prompt: " + conversation.title);
        check(conversation.entries.size() == 3, "user, tool, assistant entries: " + conversation.entries.size());
        check(conversation.entries.get(0).kind == AgentConversation.Kind.USER && "hi".equals(conversation.entries.get(0).text), "user entry");
        check(conversation.entries.get(1).kind == AgentConversation.Kind.TOOL
                && "Open Settings".equals(NativeToolPresentation.title(conversation.entries.get(1).item))
                && "Ended".equals(NativeToolPresentation.status(conversation.entries.get(1).item)), "tool entry renders with title and status");
        AgentConversation.Entry reply = conversation.entries.get(2);
        check(reply.kind == AgentConversation.Kind.ASSISTANT && reply.text.equals("Done: Wi-Fi is on.\nDetails follow.")
                && "final_answer".equals(reply.item.optString("phase")), "assistant entry is the final answer");
        check("Done: Wi-Fi is on.\nDetails follow.".equals(AgentConversation.lastReply(done)), "lastReply reads the ACP reply");

        JSONObject streaming = AcpChatFixtures.projectedConversation(true);
        check(streaming != null, "a streaming view was published");
        AgentConversation live = AgentConversation.read(streaming);
        check(live.running, "an in-progress ACP turn reads as running");
        check(live.entries.get(live.entries.size() - 1).kind == AgentConversation.Kind.ASSISTANT, "partial reply is visible while streaming");
        phoneToolsNeverShowRawNamesOrJson();
        System.out.println("AcpChatProjectionTest passed " + checks + " checks");
    }

    /** BUG-0033: release_apps (and its sibling phone tools) never show a raw tool name, server, arguments or JSON result. */
    private static void phoneToolsNeverShowRawNamesOrJson() throws Exception {
        String released = "{\"released\":[\"com.xingin.xhs\"],\"held\":[]}";
        JSONObject[] items = {
            // Codex with its namespace, Codex without one, ACP as projected (no title), ACP bare name.
            new JSONObject().put("type", "dynamicToolCall").put("namespace", "taku_phone").put("tool", "release_apps")
                    .put("arguments", new JSONObject().put("packages", new org.json.JSONArray().put("com.xingin.xhs")))
                    .put("contentItems", new org.json.JSONArray().put(new JSONObject().put("type", "inputText").put("text", released))),
            new JSONObject().put("type", "dynamicToolCall").put("tool", "release_apps")
                    .put("contentItems", new org.json.JSONArray().put(new JSONObject().put("type", "inputText").put("text", released))),
            new JSONObject().put("type", "mcpToolCall").put("server", "agy").put("tool", "taku_phone__release_apps").put("namespace", "taku_phone")
                    .put("arguments", new JSONObject().put("packages", new org.json.JSONArray().put("com.xingin.xhs")))
                    .put("result", new JSONObject().put("content", new org.json.JSONArray().put(new JSONObject().put("type", "text").put("text", released)))),
            new JSONObject().put("type", "mcpToolCall").put("server", "agy").put("tool", "release_apps")
                    .put("result", new JSONObject().put("content", new org.json.JSONArray().put(new JSONObject().put("type", "text").put("text", released)))),
        };
        for (JSONObject item : items) {
            String title = NativeToolPresentation.title(item), details = NativeToolPresentation.details(item, "");
            check("Release the background screen".equals(title), "release_apps reads as its label: " + title + " for " + item);
            check(details.isEmpty(), "no server, arguments or JSON result for a phone tool: " + details);
        }
        for (String[] tool : new String[][]{{"request_apps", "Request a background screen"}, {"screen_status", "Check the background screen"},
                {"screenshot", "View the screen"}, {"swipe", "Swipe the screen"}, {"touch", "Touch the screen"}, {"apps", "Find apps"}}) {
            JSONObject item = new JSONObject().put("type", "dynamicToolCall").put("namespace", "taku_phone").put("tool", tool[0]);
            check(tool[1].equals(NativeToolPresentation.title(item)), tool[0] + " label: " + NativeToolPresentation.title(item));
        }
        check("Use the phone".equals(NativeToolPresentation.title(new JSONObject().put("type", "dynamicToolCall").put("tool", "taku_phone__future_tool"))),
                "an unknown phone tool reads generically");
        check("other.open_app".equals(NativeToolPresentation.title(new JSONObject().put("type", "dynamicToolCall").put("tool", "other.open_app"))),
                "an unrelated namespaced tool is not mislabeled");
        JSONObject failed = new JSONObject().put("type", "dynamicToolCall").put("namespace", "taku_phone").put("tool", "open_app")
                .put("contentItems", new org.json.JSONArray().put(new JSONObject().put("type", "inputText")
                        .put("text", "{\"error\":{\"code\":\"APP_NOT_HELD\",\"message\":\"Request the App first.\"}}")));
        check("Request the App first.".equals(NativeToolPresentation.details(failed, "")), "a phone JSON failure keeps its human message");
        JSONObject plain = new JSONObject().put("type", "dynamicToolCall").put("namespace", "taku_phone").put("tool", "tap")
                .put("contentItems", new org.json.JSONArray().put(new JSONObject().put("type", "inputText").put("text", "Tapped at 10,20")));
        check("Tapped at 10,20".equals(NativeToolPresentation.details(plain, "")), "plain phone output stays");
        JSONObject other = new JSONObject().put("type", "mcpToolCall").put("server", "github").put("tool", "search")
                .put("result", new JSONObject().put("content", new org.json.JSONArray().put(new JSONObject().put("type", "text").put("text", "{\"total\":3}"))));
        check(NativeToolPresentation.details(other, "").contains("{\"total\":3}"), "another server's output is not filtered");
    }
}
