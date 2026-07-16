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
    private Integer sessionWindowSize;
    //上下文压缩触发消息数（达到这个数，就开始触发）
    private Integer summaryWindowSize;
    //上下文压缩触发词元数（达到这个数，就开始触发）
    private Integer summaryWindowToken;
    //压缩模型
    private String summaryModel;

    //启用沙盒模式
    private Boolean sandboxMode;
    //沙盒允许访问用户主目录
    private Boolean sandboxAllowUserHome;
    //沙盒使用系统接口限制
    private Boolean sandboxSystemRestrict;

    //api 重试次数
    private Integer apiRetries;
    //Mcp 重试次数
    private Integer mcpRetries;
    //模型重试次数
    private Integer modelRetries;
    //启用异步终端（增加上下文消耗，非编码用户建议关闭）
    private Boolean bashAsyncEnabled;
    //启用心智记忆（跨会话长期记忆）
    private Boolean memoryEnabled;
    //启用心智记忆隔离（按工作区隔离长期记忆）
    private Boolean memoryIsolation;

    //是否接入 MCP 服务
    private Boolean mcpEnabled;
    //是否接入 OpenAPI 服务
    private Boolean openApiEnabled;
    //启用LSP代码智能（增加上下文消耗，非编码用户建议关闭）
    private Boolean lspEnabled;

    //------------

    //http 用户代理
    private String userAgent; // "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/1.0 like claude-code; +https://solon.noear.org/)";

    //最大回合
    private Integer maxTurns; // 20
    //自我反思
    private Boolean autoRethink; //true

    //是否启用人工审核危险操作
    private Boolean hitlEnabled; //false
    //是否启用子代理模式
    private Boolean subagentEnabled; // true

    //内心思考，是否打印
    private Boolean cliThinkPrinted; //true
    //控制台打印是否简化
    private Boolean cliPrintSimplified; //true

    //是否启用 Goal 模式（Codex CLI 对齐的长任务目标模式）
    private Boolean goalsEnabled; // true


    //===================

    //Web 访问认证用户名（登录页用，留空则不启用）
    private String webAuthUser;
    //Web 访问认证密码（登录页用，留空则不启用）
    private String webAuthPass;

    //===================

    //日志级别 (TRACE/DEBUG/INFO/WARN/ERROR)
    private String logLevel;
    //日志文件大小限制，如 "10MB"
    private String logFileMaxSize;
    //日志存档保留周期（天）
    private Integer logMaxHistory;
}
