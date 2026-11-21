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
        System.out.println("AcpChatProjectionTest passed " + checks + " checks");
    }
}
