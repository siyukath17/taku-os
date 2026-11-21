package org.agentos.lab;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;

/** Public test bridge: a conversation projection produced by a real ACP chat run against the fake agent. */
public final class AcpChatFixtures {
    private AcpChatFixtures() {}

    public static JSONObject projectedConversation(boolean inProgress) throws Exception {
        File root = Files.createTempDirectory("acp-fixture").toFile();
        root.deleteOnExit();
        AcpChatRuntimeTest.Host host = new AcpChatRuntimeTest.Host(new File(root, "acp-conversations"));
        File task = new File(root, "task");
        task.mkdirs();
        AcpRuntime.Options options = AcpRuntime.Options.fromJson(new JSONObject()
                .put("binary", "/data/local/agentos/acp/bin/agy").put("cwd", "").put("progressIntervalMillis", 0));
        new AcpRuntime(options, new FakeAcpAgent(FakeAcpAgent.Scenario.NORMAL), null, null, host).run(task,
                new JSONObject().put("id", "900").put("prompt", "hi").put("permissionMode", "full").put("conversationId", AcpChatRuntimeTest.CONV));
        if (!inProgress) return host.view();
        // The newest published view in which the reply was still streaming.
        JSONObject streaming = null;
        for (JSONObject view : host.published)
            if (AcpChatRuntimeTest.lastTurn(view).getString("status").equals("inProgress")
                    && AcpChatRuntimeTest.item(AcpChatRuntimeTest.lastTurn(view), "agentMessage", 0) != null) streaming = view;
        return streaming;
    }
}
