package org.noear.solon.codecli.portal.web;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.channel.dingtalk.DingTalkLink;
import org.noear.solon.codecli.channel.feishu.FeishuLink;
import org.noear.solon.codecli.channel.wechat.WeChatClient;
import org.noear.solon.codecli.channel.wechat.WeChatLink;
import org.noear.solon.core.handle.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web 通道控制器 —— 统一管理多渠道即时通讯的绑定、解绑与状态查询。
 *
 * <p><b>职责说明：</b>
 * 提供基于 HTTP 的 REST 接口，供前端页面完成即时通讯通道的扫码绑定、凭证绑定、
 * 解绑以及状态轮询等操作。每个通道对应一组绑定/解绑/状态接口。</p>
 *
 * <p><b>支持的通道类型：</b>
 * <ul>
 *   <li>微信（WeChat）—— 通过扫码登录方式绑定</li>
 *   <li>飞书（Feishu）—— 通过 App ID / App Secret 凭证方式绑定，基于 WebSocket Stream 通信</li>
 *   <li>钉钉（DingTalk）—— 通过 AppKey / AppSecret 凭证方式绑定，基于 Stream 通信</li>
 * </ul>
 *
 * <p><b>架构位置：</b>
 * 位于 portal.web 层，作为 Web 控制器接收前端请求，将通道操作委托给
 * {@link WeChatLink}、{@link FeishuLink}、{@link DingTalkLink} 等通道适配器执行。
 * 实现 {@link Runnable} 接口，在启动时同时拉起所有通道的长连接。</p>
 *
 * @author noear 2026/5/12 created
 */
public class WebChannel implements Runnable{

    /** 微信通道适配器，负责扫码登录、会话绑定与消息转发 */
    private final WeChatLink weChatLink;

    /** 飞书通道适配器，负责 WebSocket Stream 连接、会话绑定与消息转发 */
    private final FeishuLink feishuLink;

    /** 钉钉通道适配器，负责 Stream 连接、会话绑定与消息转发 */
    private final DingTalkLink dingTalkLink;

    /**
     * 构造函数：初始化三个通道适配器。
     *
     * @param engine  AI 能力引擎，供各通道适配器调用模型能力
     * @param webGate Web 网关，提供公共配置与回调上下文
     */
    public WebChannel(HarnessEngine engine, WebGate webGate) {
        this.weChatLink = new WeChatLink(engine, webGate);
        this.feishuLink = new FeishuLink(engine, webGate);
        this.dingTalkLink = new DingTalkLink(engine, webGate);
    }

    /**
     * 启动所有通道适配器的长连接监听。
     * 依次启动微信、飞书、钉钉三个通道的运行循环。
     */
    @Override
    public void run() {
        weChatLink.run();
        feishuLink.run();
        dingTalkLink.run();
    }

    // ==================== 微信（WeChat）通道接口 ====================

    /**
     * 获取微信扫码登录二维码。
     *
     * <p>调用微信接口获取二维码图片及其标识，用于前端展示扫码登录入口。</p>
     *
     * @param sessionId 当前会话标识，用于将二维码与具体会话关联
     * @return 包含 qrcode（二维码标识）、qrcode_img_content（二维码图片内容）、sessionId 的结果
     */
    @Get
    @Mapping("/web/chat/wechat/qrcode")
    public Result<Map> wechatQrcode(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        Map<String, String> qrResult = WeChatClient.fetchQRCode();
        if (qrResult == null) {
            return Result.failure("获取微信二维码失败，请确认网络可访问 ilinkai.weixin.qq.com");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("qrcode", qrResult.get("qrcode"));
        data.put("qrcode_img_content", qrResult.get("qrcode_img_content"));
        data.put("sessionId", sessionId);
        return Result.succeed(data);
    }

    /**
     * 轮询微信扫码状态。
     *
     * <p>根据二维码标识查询当前扫码进度（等待扫码、已扫码待确认、已确认等）。
     * 当状态为 "confirmed" 时，自动将机器人绑定到对应会话。</p>
     *
     * @param qrcode     二维码标识，由 {@link #wechatQrcode} 接口返回
     * @param sessionId  当前会话标识
     * @return 包含扫码状态信息的结果；确认后额外触发自动绑定
     */
    @Get
    @Mapping("/web/chat/wechat/qrcode/status")
    public Result<Map> wechatQrcodeStatus(@Param("qrcode") String qrcode,
                                          @Param("sessionId") String sessionId) {
        if (qrcode == null || qrcode.isEmpty()) {
            return Result.failure("qrcode is required");
        }
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        Map<String, String> statusResult = WeChatClient.pollQRStatus(qrcode);
        if (statusResult == null) {
            Map<String, Object> errData = new LinkedHashMap<>();
            errData.put("status", "error");
            return Result.succeed(errData);
        }

        // 扫码确认后自动绑定：提取令牌与用户信息，关联到当前会话
        if ("confirmed".equals(statusResult.get("status"))) {
            String botToken = statusResult.get("bot_token");
            String ilinkBotId = statusResult.get("ilink_bot_id");
            String ilinkUserId = statusResult.get("ilink_user_id");

            weChatLink.bindSession(sessionId, botToken, ilinkBotId, ilinkUserId);
        }

        Map<String, Object> data = new LinkedHashMap<>(statusResult);
        return Result.succeed(data);
    }

    /**
     * 解绑微信通道。
     *
     * <p>解除指定会话与微信机器人的绑定关系，之后该会话不再接收微信消息。</p>
     *
     * @param sessionId 待解绑的会话标识
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/wechat/unbind")
    public Result wechatUnbind(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        weChatLink.unbindSession(sessionId);
        return Result.succeed();
    }

    /**
     * 查询会话的微信绑定状态。
     *
     * @param sessionId 待查询的会话标识
     * @return 包含 bound（是否已绑定）的结果
     */
    @Get
    @Mapping("/web/chat/wechat/status")
    public Result<Map> wechatStatus(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bound", weChatLink.isBound(sessionId));
        return Result.succeed(data);
    }

    // ==================== 飞书（Feishu）通道接口 ====================

    /**
     * 绑定飞书到指定会话。
     *
     * <p>提交飞书应用的 App ID 和 App Secret，启动 WebSocket Stream 连接，
     * 等待飞书侧自动完成事件订阅绑定。</p>
     *
     * @param sessionId  当前会话标识
     * @param appId      飞书应用的 App ID
     * @param appSecret  飞书应用的 App Secret
     * @return 启动成功返回成功结果；失败返回错误提示
     */
    @Post
    @Mapping("/web/chat/feishu/bind")
    public Result feishuBind(@Param("sessionId") String sessionId,
                             @Param("appId") String appId,
                             @Param("appSecret") String appSecret) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }
        if (appId == null || appId.isEmpty()) {
            return Result.failure("App ID 是必填项");
        }
        if (appSecret == null || appSecret.isEmpty()) {
            return Result.failure("App Secret 是必填项");
        }
        if (feishuLink == null) {
            return Result.failure("飞书通道未启用");
        }

        boolean ok = feishuLink.startStream(appId, appSecret, sessionId);
        if (!ok) {
            return Result.failure("飞书连接启动失败，请检查 App ID 和 App Secret");
        }
        return Result.succeed();
    }

    /**
     * 解绑飞书通道。
     *
     * <p>解除指定会话与飞书应用的绑定关系，并断开对应的 Stream 连接。</p>
     *
     * @param sessionId 待解绑的会话标识
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/feishu/unbind")
    public Result feishuUnbind(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }
        if (feishuLink != null) {
            feishuLink.unbindSession(sessionId);
        }
        return Result.succeed();
    }

    /**
     * 查询会话的飞书绑定状态。
     *
     * <p>返回包含 Stream 连接状态的详细信息，供前端轮询判断绑定进度。</p>
     *
     * @param sessionId 待查询的会话标识
     * @return 包含 bound（是否已绑定）、streamStarted（Stream 是否已启动）、pending（是否等待绑定确认）的结果
     */
    @Get
    @Mapping("/web/chat/feishu/status")
    public Result<Map> feishuStatus(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }
        if (feishuLink == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bound", false);
            data.put("streamStarted", false);
            data.put("pending", false);
            return Result.succeed(data);
        }
        return Result.succeed(feishuLink.getStreamStatus(sessionId));
    }

    // ==================== 钉钉（DingTalk）通道接口 ====================

    /**
     * 绑定钉钉到指定会话。
     *
     * <p>提交钉钉应用的 AppKey 和 AppSecret，启动 Stream 长连接，
     * 用户在钉钉端向机器人发送消息后自动完成绑定。</p>
     *
     * @param sessionId  当前会话标识
     * @param appKey     钉钉应用的 AppKey
     * @param appSecret  钉钉应用的 AppSecret
     * @return 启动成功返回提示信息；失败返回错误提示
     */
    @Post
    @Mapping("/web/chat/dingtalk/bind")
    public Result dingtalkBind(@Param("sessionId") String sessionId,
                               @Param("appKey") String appKey,
                               @Param("appSecret") String appSecret) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }
        if (appKey == null || appKey.isEmpty()) {
            return Result.failure("appKey 不能为空");
        }
        if (appSecret == null || appSecret.isEmpty()) {
            return Result.failure("appSecret 不能为空");
        }
        if (dingTalkLink == null) {
            return Result.failure("钉钉通道未启用");
        }

        boolean ok = dingTalkLink.startStream(appKey, appSecret, sessionId);
        if (!ok) {
            return Result.failure("启动 Stream 连接失败，请检查 AppKey 和 AppSecret");
        }
        return Result.succeed("已启动连接，请在钉钉上发消息给机器人完成绑定");
    }

    /**
     * 解绑钉钉通道。
     *
     * <p>解除指定会话与钉钉应用的绑定关系，并断开对应的 Stream 连接。</p>
     *
     * @param sessionId 待解绑的会话标识
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/dingtalk/unbind")
    public Result dingtalkUnbind(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }
        if (dingTalkLink != null) {
            dingTalkLink.unbindSession(sessionId);
        }
        return Result.succeed();
    }

    /**
     * 查询会话的钉钉绑定状态。
     *
     * <p>供前端轮询使用，返回 Stream 连接状态与绑定进度。</p>
     *
     * @param sessionId 待查询的会话标识
     * @return 包含 bound（是否已绑定）、streamStarted（Stream 是否已启动）、pending（是否等待绑定确认）的结果
     */
    @Get
    @Mapping("/web/chat/dingtalk/status")
    public Result<Map> dingtalkStatus(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }
        if (dingTalkLink == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bound", false);
            data.put("streamStarted", false);
            data.put("pending", false);
            return Result.succeed(data);
        }
        return Result.succeed(dingTalkLink.getStreamStatus(sessionId));
    }

    // ==================== 通道实例访问器 ====================

    /**
     * 获取微信通道适配器实例。
     *
     * <p>供外部组件（如 Configurator）注册启动或进行额外配置。</p>
     *
     * @return 微信通道适配器
     */
    public WeChatLink getWeChatLink() {
        return weChatLink;
    }

    /**
     * 获取飞书通道适配器实例。
     *
     * @return 飞书通道适配器
     */
    public FeishuLink getFeishuLink() {
        return feishuLink;
    }

    /**
     * 获取钉钉通道适配器实例。
     *
     * @return 钉钉通道适配器
     */
    public DingTalkLink getDingTalkLink() {
        return dingTalkLink;
    }
}
