package org.noear.solon.codecli.config.entity;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 *
 * @author noear 2026/5/31 created
 *
 */
@Getter
@Setter
public class GeneralGroupDo implements Serializable {
    //会话历史窗口大小（即，新指令时使用几条历史消息）
    private int sessionWindowSize = 12;
    //上下文压缩触发消息数（达到这个数，就开始触发）
    private int compressionThresholdMessages = 100;
    //上下文压缩触发上下文比例（百分比，达到这个比例，就开始触发）
    private int compressionThresholdPercent = 75;

    //启用沙盒模式
    private boolean sandboxMode = true;
    //沙盒允许访问用户主目录
    private boolean sandboxAllowUserHome = true;
    //沙盒使用系统接口限制
    private boolean sandboxSystemRestrict = false;

    //api 重试次数
    private int apiRetries = 3;
    //Mcp 重试次数
    private int mcpRetries = 3;
    //模型重试次数
    private int modelRetries = 3;

    //启用异步终端（增加上下文消耗，非编码用户建议关闭）
    private boolean bashAsyncEnabled = false;
    //启用心智记忆
    private boolean memoryEnabled = true;
    //心智记忆-语义匹配条数（按对话内容匹配的记忆条数）
    private int memoryRelevanceCount = 6;
    //心智记忆-重要度兜底条数（按重要度兜底的记忆条数）
    private int memoryPriorityCount = 5;
    //心智记忆-摘要长度（记忆摘要的截断字符数）
    private int memorySummaryLength = 80;

    //是否接入 MCP 服务
    private boolean mcpEnabled = true;
    //是否接入 OpenAPI 服务
    private boolean openApiEnabled = true;
    //启用LSP代码智能（增加上下文消耗，非编码用户建议关闭）
    private boolean lspEnabled = true;

    //------------

    //http 用户代理
    private String userAgent = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/1.0 like claude-code; +https://solon.noear.org/)";

    //最大回合
    private int maxTurns = 20; // 20
    //自我反思
    private boolean autoRethink = true; //true

    //是否启用人工审核危险操作
    private boolean hitlEnabled = false; //false
    //是否启用子代理模式
    private boolean subagentEnabled = true; // true
    //是否启用 Manage 工具（ManagerTalent：AI 动态添加模型/MCP/OpenAPI/定时任务）
    private boolean managerEnabled = false; // false

    //内心思考，是否打印
    private boolean cliThinkPrinted = false; //true
    //控制台打印是否简化
    private boolean cliPrintSimplified = true; //true

    //是否启用 Goal 模式（Codex CLI 对齐的长任务目标模式）
    private boolean goalsEnabled = true; // true

    //是否启用 Loop 工具（LoopTalent：AI 查询/停止/暂停/触发定时循环任务，含一次性定时）
    private boolean loopsEnabled = true; // true

    //当前激活的皮肤（default / 预置名 / 本地安装名；空或 default 表示默认）
    private String activeSkin;

    //界面字体-正文字族（空表示跟随主题/皮肤默认）
    private String uiFontFamily;
    //界面字体-等宽字族（空表示跟随主题/皮肤默认）
    private String uiFontMono;
    //界面字体-缩放倍率（0.85 ~ 1.5；空或 1 表示默认）
    private Double uiFontScale;

    //新建对话的默认思考模式（on|off；空表示 auto，不干预，跟随模型/供应商默认）
    private String defaultThinkingMode;
    //新建对话的默认推理强度（low|medium|high|max；空表示 auto，不干预）
    private String defaultReasoningEffort;


    //===================

    //Web 访问认证用户名（登录页用，留空则不启用）
    private String webAuthUser;
    //Web 访问认证密码（登录页用，留空则不启用）
    private String webAuthPass;

    //===================

    //HTTP 代理主机地址（如 "127.0.0.1"），留空则不启用代理
    private String proxyHost;
    //HTTP 代理端口（如 1080），默认 0 表示不启用
    private int proxyPort;
    //NO_PROXY 排除列表（逗号分隔），匹配的主机/域名将不使用代理
    private String noProxy;

    //===================

    //日志级别 (TRACE/DEBUG/INFO/WARN/ERROR)
    private String logLevel;
    //日志文件大小限制，如 "10MB"
    private String logFileMaxSize;
    //日志存档保留周期（天）
    private Integer logMaxHistory;
}
