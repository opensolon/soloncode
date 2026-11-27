package org.noear.solon.codecli.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeToolHistoryExtensionTest {
    @Test
    void onlyIncludesCompleteNativeExchangesFromTheAnchoredTurn() {
        ChatMessage user = ChatMessage.ofUser("List files");
        user.addMetadata(AgentTrace.META_RUN_ID, "r");
        AssistantMessage parallel = new AssistantMessage("Inspecting", "Thinking",
                Arrays.asList(new ToolCall("0", "a", "ls", "{}", Collections.emptyMap()),
                        new ToolCall("1", "b", "read", "{}", Collections.emptyMap())), Collections.emptyList());
        ChatMessage a = ChatMessage.ofTool("file.txt", "ls", "a");
        ChatMessage b = ChatMessage.ofTool("raw bytes", "read", "b");
        assertEquals(Arrays.asList(parallel, b, a), NativeToolHistoryExtension.completeExchanges(
                Arrays.asList(parallel, a, b, user, parallel, b, a, parallel, a, ChatMessage.ofAssistant("Done")), "r"));
        assertEquals(Collections.emptyList(), NativeToolHistoryExtension.completeExchanges(
                Arrays.asList(user, parallel, b, b), "r"));
    }
}
