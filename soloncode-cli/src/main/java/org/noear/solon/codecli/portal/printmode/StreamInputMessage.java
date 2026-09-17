/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.printmode;

import org.noear.snack4.ONode;

/**
 * {@code --input-format stream-json} 模式下从 stdin 读到的一行消息。
 *
 * <p>对齐 Claude Code 的流式输入信封：每行是一个完整 JSON 对象，
 * 用户消息形如
 * <pre>
 * {"type":"user","message":{"role":"user","content":"你好"}}
 * {"type":"user","message":{"role":"user","content":[{"type":"text","text":"你好"}]}}
 * </pre>
 * 控制帧形如
 * <pre>
 * {"type":"control_request","request_id":"req-1","request":{"subtype":"interrupt"}}
 * </pre>
 * </p>
 *
 * <p>解析是纯函数，不涉及 IO 与 Agent，便于单测。</p>
 *
 * @author noear
 */
public class StreamInputMessage {

    public enum Kind {
        /** 一轮用户提问 */
        USER,
        /** 中断当前轮次 */
        INTERRUPT,
        /** 可安全忽略的行（空行或明确的出向回显类型） */
        IGNORED,
        /** 无法解析或缺少必要字段 */
        MALFORMED,
        /** 是控制帧，但 subtype 不受支持 */
        UNSUPPORTED_CONTROL,
        /** stdin 已到达 EOF（由输入泵线程产生，{@link #parse} 永不返回） */
        EOF
    }

    private final Kind kind;
    private final String text;
    private final String requestId;
    private final String detail;
    private final ONode raw;

    private StreamInputMessage(Kind kind, String text, String requestId, String detail, ONode raw) {
        this.kind = kind;
        this.text = text;
        this.requestId = requestId;
        this.detail = detail;
        this.raw = raw;
    }

    public Kind getKind() {
        return kind;
    }

    /** 用户提问文本（仅 USER 有值） */
    public String getText() {
        return text;
    }

    /** 控制帧的 request_id，用于回 control_response（可能为 null） */
    public String getRequestId() {
        return requestId;
    }

    /** MALFORMED / UNSUPPORTED_CONTROL 的原因说明 */
    public String getDetail() {
        return detail;
    }

    /** 原始 JSON 节点，供 --replay-user-messages 原样回显（可能为 null） */
    public ONode getRaw() {
        return raw;
    }

    // ========== 工厂 ==========

    static StreamInputMessage user(String text, ONode raw) {
        return new StreamInputMessage(Kind.USER, text, null, null, raw);
    }

    static StreamInputMessage interrupt(String requestId) {
        return new StreamInputMessage(Kind.INTERRUPT, null, requestId, null, null);
    }

    static StreamInputMessage ignored() {
        return new StreamInputMessage(Kind.IGNORED, null, null, null, null);
    }

    static StreamInputMessage malformed(String detail) {
        return new StreamInputMessage(Kind.MALFORMED, null, null, detail, null);
    }

    static StreamInputMessage unsupportedControl(String requestId, String detail) {
        return new StreamInputMessage(Kind.UNSUPPORTED_CONTROL, null, requestId, detail, null);
    }

    /** stdin 结束哨兵：入队后作为主循环的终止标记（FIFO 保证先排的轮次不会被丢） */
    static StreamInputMessage eof() {
        return new StreamInputMessage(Kind.EOF, null, null, null, null);
    }

    // ========== 解析 ==========

    /**
     * 解析一行 JSONL 输入。
     *
     * <p>容错取向：单行出错只影响该行（返回 MALFORMED），不终止整个会话——
     * 上游 SDK 与 shell 管道都可能混入空行或日志行。</p>
     *
     * @param line 一行原始文本（可为 null）
     * @return 解析结果，永不为 null
     */
    public static StreamInputMessage parse(String line) {
        if (line == null || line.trim().isEmpty()) {
            return ignored();
        }

        ONode node;
        try {
            node = ONode.ofJson(line.trim());
        } catch (Exception e) {
            return malformed("invalid JSON: " + e.getMessage());
        }

        if (node == null || !node.isObject()) {
            return malformed("expected a JSON object per line");
        }

        String type = getStringOrNull(node, "type");
        if (type == null) {
            return malformed("missing 'type' field");
        }

        switch (type) {
            case "user":
                return parseUser(node);
            // control_request 是 Claude Code 的嵌套形态；control 是扁平变体，两者都接受
            case "control_request":
            case "control":
                return parseControl(node);
            default:
                // 只忽略明确的出向类型，拼错 type 必须让上游看到 input_error，
                // 否则调用方会永远等待一个根本不会开始的轮次。
                if ("assistant".equals(type) || "result".equals(type)
                        || "control_response".equals(type) || "system".equals(type)
                        || "error".equals(type)) {
                    return ignored();
                }
                return malformed("unsupported input type: " + type);
        }
    }

    private static StreamInputMessage parseUser(ONode node) {
        ONode message = node.get("message");
        if (message == null || !message.isObject()) {
            return malformed("user message missing 'message' object");
        }

        String role = getStringOrNull(message, "role");
        if (!"user".equals(role)) {
            return malformed("user message requires 'message.role' to be 'user'");
        }

        ONode content = message.get("content");
        if (content == null || content.isNull()) {
            return malformed("user message missing 'message.content'");
        }

        if (content.isValue()) {
            // 不能用 content.getString()：ONode 会把数字/布尔也转成字符串，
            // {"content":123.5} 会被当成合法提示词“123.5”送进模型
            Object value = content.getValue();
            if (!(value instanceof String)) {
                return malformed("'message.content' must be a string or an array of blocks");
            }
            String text = (String) value;
            if (text.trim().isEmpty()) {
                return malformed("user message has empty text content");
            }
            return user(text, node);
        }

        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            int skipped = 0;
            for (ONode block : content.getArray()) {
                if (block == null || !block.isObject()) {
                    skipped++;
                    continue;
                }
                String blockType = getStringOrNull(block, "type");
                if ("text".equals(blockType)) {
                    String text = getStringOrNull(block, "text");
                    if (text != null && !text.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(text);
                    }
                } else {
                    // image / document 等块当前引擎不支持，记录跳过数量而不是静默丢弃
                    skipped++;
                }
            }
            if (sb.length() == 0) {
                return malformed(skipped > 0
                        ? "user message has no text block (" + skipped + " unsupported block(s) skipped)"
                        : "user message has no text block");
            }
            return user(sb.toString(), node);
        }

        return malformed("'message.content' must be a string or an array of blocks");
    }

    private static StreamInputMessage parseControl(ONode node) {
        String requestId = getStringOrNull(node, "request_id");

        // 嵌套形态优先：{"request":{"subtype":"interrupt"}}；否则回落到平铺 subtype
        String subtype = null;
        ONode request = node.get("request");
        if (request != null && request.isObject()) {
            subtype = getStringOrNull(request, "subtype");
            if (requestId == null) {
                requestId = getStringOrNull(request, "request_id");
            }
        }
        if (subtype == null) {
            subtype = getStringOrNull(node, "subtype");
        }

        if (subtype == null) {
            return malformed("control request missing 'subtype'");
        }

        if ("interrupt".equals(subtype)) {
            return interrupt(requestId);
        }

        return unsupportedControl(requestId, "unsupported control subtype: " + subtype);
    }

    /**
     * 取字符串字段；缺失、null 或非字符串一律返回 null。
     *
     * <p>ONode 的 {@code getString()} 会把数字/布尔也转成字符串，这里显式收紧，
     * 避免 {@code {"type":1}} 之类的输入被当成合法类型名。</p>
     */
    private static String getStringOrNull(ONode node, String key) {
        if (node == null) {
            return null;
        }
        ONode child = node.get(key);
        if (child == null || child.isNull() || !child.isValue()) {
            return null;
        }
        Object value = child.getValue();
        if (value instanceof String) {
            String s = (String) value;
            return s.isEmpty() ? null : s;
        }
        return null;
    }
}
