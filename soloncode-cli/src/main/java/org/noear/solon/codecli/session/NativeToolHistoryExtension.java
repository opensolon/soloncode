package org.noear.solon.codecli.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.util.TraceUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Retains the previous completed turn's native tool exchanges for the next request. */
public final class NativeToolHistoryExtension extends AbsHarnessExtension {
    private static final String MODEL_KEY = "soloncode.nativeToolHistory.model";
    private final Map<AgentSession, PreviousTurn> pending = Collections.synchronizedMap(new WeakHashMap<>());
    private final NativeHistoryInterceptor interceptor = new NativeHistoryInterceptor();

    @Override
    public void onInput(HarnessEngine engine, HarnessInput input) {
        AgentSession session = input.getSession();
        if (session == null || input.isResume() || input.isHitlDecision() || !isEnabled()) {
            return;
        }
        pending.remove(session);
        ReActTrace trace = TraceUtil.getCurrentTrace(session);
        if (trace == null || session.isPending() || !(trace.getExtra(MODEL_KEY) instanceof String)) {
            return;
        }
        String runId = trace.getRunId();
        List<ChatMessage> tail = session.getLatestMessages(1);
        ChatMessage last = tail.isEmpty() ? null : tail.get(tail.size() - 1);
        if (!(last instanceof AssistantMessage) || !runId.equals(last.getMetadataAs(AgentTrace.META_RUN_ID))) {
            return;
        }
        List<ChatMessage> exchanges = completeExchanges(trace.getWorkingMemory().getMessages(), runId);
        if (!exchanges.isEmpty()) {
            pending.put(session, new PreviousTurn(runId, (String) trace.getExtra(MODEL_KEY), exchanges));
        }
    }

    @Override
    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend options) {
        if (!isDelegated) {
            options.interceptorAdd(interceptor);
        }
    }

    static List<ChatMessage> completeExchanges(List<ChatMessage> memory, String runId) {
        List<ChatMessage> out = new ArrayList<>();
        boolean inRun = false;
        for (int i = 0; i < memory.size(); i++) {
            ChatMessage message = memory.get(i);
            if (message instanceof UserMessage && runId.equals(message.getMetadataAs(AgentTrace.META_RUN_ID))) {
                inRun = true;
            }
            if (!inRun || message.hasMetadata(AgentTrace.META_FIRST) || !(message instanceof AssistantMessage)) {
                continue;
            }
            List<ToolCall> calls = ((AssistantMessage) message).getToolCalls();
            if (calls == null || calls.isEmpty() || calls.size() >= memory.size() - i) {
                continue;
            }
            Set<String> ids = new HashSet<>();
            for (ToolCall call : calls) {
                if (call == null || call.getId() == null || call.getId().isEmpty() || !ids.add(call.getId())) {
                    ids.clear();
                    break;
                }
            }
            for (int offset = 1; offset <= calls.size() && !ids.isEmpty(); offset++) {
                ChatMessage result = memory.get(i + offset);
                if (!(result instanceof ToolMessage) || result.hasMetadata(AgentTrace.META_FIRST)
                        || !ids.remove(((ToolMessage) result).getToolCallId())) {
                    break;
                }
                if (ids.isEmpty() && offset == calls.size()) {
                    out.addAll(memory.subList(i, i + offset + 1));
                    i += offset;
                }
            }
        }
        return out;
    }

    private final class NativeHistoryInterceptor extends AbsReActInterceptor {
        @Override
        public void onAgentStart(ReActTrace trace) {
            if (!NativeToolHistoryExtension.this.isEnabled() || trace.getTurnCount() != 0) {
                return;
            }
            String modelKey = modelKey(trace);
            if (modelKey == null) {
                return;
            }
            trace.setExtra(MODEL_KEY, modelKey);
            AgentSession session = trace.getSession();
            PreviousTurn previous = pending.remove(session);
            if (previous == null || !modelKey.equals(previous.modelKey) || trace.getOptions().getSessionWindowSize() <= 0) {
                return;
            }
            List<ChatMessage> tail = session.getLatestMessages(3);
            int n = tail.size();
            if (n < 3 || !(tail.get(n - 3) instanceof UserMessage) || !(tail.get(n - 2) instanceof AssistantMessage)
                    || !(tail.get(n - 1) instanceof UserMessage)
                    || !previous.runId.equals(tail.get(n - 3).getMetadataAs(AgentTrace.META_RUN_ID))
                    || !previous.runId.equals(tail.get(n - 2).getMetadataAs(AgentTrace.META_RUN_ID))
                    || !trace.getRunId().equals(tail.get(n - 1).getMetadataAs(AgentTrace.META_RUN_ID))) {
                return;
            }
            List<ChatMessage> memory = new ArrayList<>(trace.getWorkingMemory().getMessages());
            int m = memory.size();
            if (m < 3 || memory.get(m - 3) != tail.get(n - 3) || !(memory.get(m - 2) instanceof AssistantMessage)
                    || memory.get(m - 1) != tail.get(n - 1)) {
                return;
            }
            for (ChatMessage message : previous.exchanges) {
                message.addMetadata(AgentTrace.META_RUN_ID, previous.runId);
                message.addMetadata(AgentTrace.META_FIRST, 1);
            }
            memory.addAll(m - 2, previous.exchanges);
            trace.getWorkingMemory().replaceMessages(memory);
        }
    }

    private static String modelKey(ReActTrace trace) {
        ChatModel model = trace.getOptions().getChatModel();
        ChatConfigReadonly config = model == null ? null : model.getConfig();
        if (config == null) {
            return null;
        }
        String identity = String.join("\n", Arrays.asList(String.valueOf(trace.getAgentName()),
                String.valueOf(config.getNameOrModel()), String.valueOf(config.getModel()),
                String.valueOf(config.getStandardOrProvider()), String.valueOf(config.getApiUrl())));
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class PreviousTurn {
        final String runId;
        final String modelKey;
        final List<ChatMessage> exchanges;

        PreviousTurn(String runId, String modelKey, List<ChatMessage> exchanges) {
            this.runId = runId;
            this.modelKey = modelKey;
            this.exchanges = exchanges;
        }
    }
}
