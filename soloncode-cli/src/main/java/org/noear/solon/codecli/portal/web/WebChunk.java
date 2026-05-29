package org.noear.solon.codecli.portal.web;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Web 通道消息块 —— 用于在 Web 门户与后端服务之间传递流式响应数据的最小单元。
 *
 * <h3>职责说明</h3>
 * <p>封装单条消息片段的类型、文本内容与相关元数据，作为 SSE / WebSocket 等推送协议的标准载荷载体。</p>
 *
 * <h3>type 类型枚举</h3>
 * <table>
 *   <tr><th>type 值</th><th>含义</th></tr>
 *   <tr><td>{@code text}</td><td>普通文本输出，通常为最终呈现给用户的回复内容</td></tr>
 *   <tr><td>{@code reason}</td><td>推理过程文本，表示模型正在进行的中间思考/分析过程</td></tr>
 *   <tr><td>{@code action}</td><td>动作说明文本，描述当前正在执行的操作（如调用工具前的简要说明）</td></tr>
 *   <tr><td>{@code command}</td><td>命令文本，表示需要前端展示或执行的命令内容</td></tr>
 *   <tr><td>{@code hitl}</td><td>人机协同中断（Human-in-the-Loop），暂停执行以等待人工审批或确认</td></tr>
 *   <tr><td>{@code rewind}</td><td>回退指令，表示需要撤销或回退之前若干步操作</td></tr>
 *   <tr><td>{@code done}</td><td>完成信号，表示当前响应流已全部发送完毕</td></tr>
 *   <tr><td>{@code error}</td><td>错误信息，表示处理过程中发生了异常</td></tr>
 * </table>
 *
 * <h3>架构位置</h3>
 * <p>位于 {@code portal.web} 层，属于 Web 门户模块的内部传输对象（DTO），
 * 由后端 Agent 执行引擎产出，经 Web 控制器推送至前端客户端。</p>
 *
 * @author noear 2026/5/8 created
 */
@Getter
@Setter
public class WebChunk {

    /**
     * 空消息块常量，用于表示无内容的占位实例。
     * 当 type 为 {@code null} 时，{@link #isNotEmpty(WebChunk)} 将返回 {@code false}。
     */
    public static final WebChunk EMPTY = new WebChunk();

    /**
     * 判断给定消息块是否为非空（即包含有效的 type 信息）。
     *
     * @param chunk 待检测的消息块，可以为 {@code null}
     * @return 当 chunk 不为 null 且 type 已赋值时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isNotEmpty(WebChunk chunk) {
        return chunk != null && chunk.type != null;
    }


    /** 会话标识，关联到具体的用户会话上下文。 */
    private String sessionId;

    /**
     * 消息块类型标识。
     * 取值范围见类级文档中的 type 类型枚举表（text / reason / action / command / hitl / rewind / done / error）。
     */
    private String type;

    /** 消息块的文本内容，具体含义由 type 决定（如正文、推理过程、命令、错误描述等）。 */
    private String text;

    /** 工具名称，仅在 type 为 {@code hitl} 时使用，表示需要人工审批的工具标识。 */
    private String toolName;

    /** 工具调用参数映射，保留字段，可用于携带结构化的工具调用参数。 */
    private Map<String, Object> args;

    /** 命令内容，仅在 type 为 {@code hitl} 时使用，表示需要人工审批的命令文本。 */
    private String command;

    /** 消息块创建时间戳（ epoch 毫秒），由工厂方法自动填充。 */
    private Long createdAt;

    /**
     * 创建「完成」消息块。
     * <p>type 为 {@code done}，表示当前响应流已全部发送完毕，前端收到后可结束等待状态。</p>
     *
     * @return 不携带文本内容的完成信号块
     */
    public static WebChunk ofDone() {
        WebChunk tmp = new WebChunk();
        tmp.type = "done";
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「错误」消息块（基于字符串描述）。
     * <p>type 为 {@code error}，用于向前端传递处理过程中产生的错误信息。</p>
     *
     * @param text 错误描述文本
     * @return 携带错误描述的消息块
     */
    public static WebChunk ofError(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "error";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「错误」消息块（基于异常对象）。
     * <p>type 为 {@code error}，自动从异常对象中提取错误信息；
     * 若异常消息为 {@code null}，则使用 "Unknown error" 作为兜底描述。</p>
     *
     * @param err 异常对象
     * @return 携带异常描述的消息块
     */
    public static WebChunk ofError(Throwable err) {
        WebChunk tmp = new WebChunk();
        tmp.type = "error";
        tmp.text = (err.getMessage() == null ? "Unknown error" : err.getMessage());
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「普通文本」消息块。
     * <p>type 为 {@code text}，通常为最终呈现给用户的回复正文内容。</p>
     *
     * @param text 文本内容
     * @return 携带普通文本的消息块
     */
    public static WebChunk ofText(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "text";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「推理过程」消息块。
     * <p>type 为 {@code reason}，表示模型正在进行的中间思考或分析过程，
     * 前端通常以折叠或特殊样式展示。</p>
     *
     * @param text 推理过程文本
     * @return 携带推理文本的消息块
     */
    public static WebChunk ofReason(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "reason";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }


    /**
     * 创建「动作说明」消息块。
     * <p>type 为 {@code action}，描述当前正在执行的操作（如调用工具前的简要说明），
     * 用于向前端指示 Agent 的行为意图。</p>
     *
     * @param text 动作描述文本
     * @return 携带动作说明的消息块
     */
    public static WebChunk ofAction(String text) {
        WebChunk tmp = new WebChunk();
        tmp.type = "action";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「命令」消息块。
     * <p>type 为 {@code command}，表示需要前端展示或执行的命令内容。</p>
     *
     * @param text 命令文本
     * @return 携带命令内容的消息块
     */
    public static WebChunk ofCommand(String text){
        WebChunk tmp = new WebChunk();
        tmp.type = "command";
        tmp.text = text;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「回退」消息块。
     * <p>type 为 {@code rewind}，表示需要撤销或回退之前若干步操作，
     * 前端据此调整会话上下文状态。</p>
     *
     * @param count 需要回退的步数
     * @return 携带回退步数的消息块（步数存储在 text 字段中）
     */
    public static WebChunk ofRewind(int count) {
        WebChunk tmp = new WebChunk();
        tmp.type = "rewind";
        tmp.text = String.valueOf(count);
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「用户输入」消息块。
     * <p>type 为 {@code user_input}，用于后端推送的自动化任务（如 Loop 定时任务）中，
     * 将用户提示词显示到前端对话记录中，避免对话只显示 AI 回复而无用户侧消息的问题。</p>
     *
     * @param text 用户输入文本
     * @param source 来源标识（如 "Loop"）
     * @return 携带用户输入文本的消息块
     */
    public static WebChunk ofUserInput(String text, String source) {
        WebChunk tmp = new WebChunk();
        tmp.type = "user_input";
        tmp.text = text;
        tmp.toolName = source; // 复用 toolName 字段传递来源标识
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }

    /**
     * 创建「人机协同中断」消息块。
     * <p>type 为 {@code hitl}（Human-in-the-Loop），表示执行流程暂停，
     * 等待人工对指定工具调用进行审批或确认后才会继续执行。</p>
     *
     * @param toolName 需要人工审批的工具名称
     * @param command  需要人工审批的命令文本
     * @return 携带工具名与命令内容的人机协同消息块
     */
    public static WebChunk ofHitl(String toolName, String command) {
        WebChunk tmp = new WebChunk();
        tmp.type = "hitl";
        tmp.toolName = toolName;
        tmp.command = command;
        tmp.createdAt = Instant.now().toEpochMilli();

        return tmp;
    }
}
