package org.noear.solon.codecli.portal.web;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.channel.dingtalk.DingTalkAppRegistration;
import org.noear.solon.codecli.channel.dingtalk.DingTalkLink;
import org.noear.solon.codecli.channel.dingtalk.DingTalkQRBindManager;
import org.noear.solon.codecli.channel.feishu.FeishuAppRegistration;
import org.noear.solon.codecli.channel.feishu.FeishuLink;
import org.noear.solon.codecli.channel.feishu.FeishuQRBindManager;
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
 *   <li>钉钉（DingTalk）—— 通过 AppKey / AppSecret 凭证方式绑定，基于 Stream 通信；<br/>
 *       也支持通过二维码扫码绑定（Device Authorization Grant 流程）</li>
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

    /** 飞书扫码绑定管理器 */
    private final FeishuQRBindManager feishuQRBindManager;

    /** 钉钉扫码绑定管理器 */
    private final DingTalkQRBindManager dingtalkQRBindManager;

    /**
     * 构造函数：初始化三个通道适配器和扫码绑定管理器。
     *
     * @param engine  AI 能力引擎，供各通道适配器调用模型能力
     * @param webGate Web 网关，提供公共配置与回调上下文
     */
    public WebChannel(HarnessEngine engine, WebGate webGate) {
        this.weChatLink = new WeChatLink(engine, webGate);
        this.feishuLink = new FeishuLink(engine, webGate);
        this.dingTalkLink = new DingTalkLink(engine, webGate);
        this.feishuQRBindManager = new FeishuQRBindManager();
        this.dingtalkQRBindManager = new DingTalkQRBindManager();
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
        // 清理可能的QR绑定会话
        feishuQRBindManager.cancelQrBinding(sessionId);
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

    /**
     * 开始飞书扫码绑定。
     *
     * <p>通过 Device Authorization Grant 流程获取二维码 URL，
     * 用户使用飞书 App 扫码并授权后自动完成绑定。</p>
     *
     * @param sessionId 当前会话标识
     * @return 包含 qrUrl、expiresIn、interval 的结果
     */
    @Post
    @Mapping("/web/chat/feishu/qrcode")
    public Result<Map> feishuQrcode(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        try {
            FeishuQRBindManager.BindStartResult result = feishuQRBindManager.startQrBinding(sessionId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("qrUrl", result.qrUrl);
            data.put("expiresIn", result.expiresIn);
            data.put("interval", result.interval);
            data.put("sessionId", sessionId);
            return Result.succeed(data);
        } catch (Exception e) {
            return Result.failure("获取飞书二维码失败: " + e.getMessage());
        }
    }

    /**
     * 轮询飞书扫码绑定状态。
     *
     * <p>当用户扫码授权成功后，自动获取 App ID、App Secret 和用户 openId，
     * 并调用飞书通道完成会话绑定和 Stream 连接启动。</p>
     *
     * @param sessionId 当前会话标识
     * @return 包含绑定状态的结果
     */
    @Get
    @Mapping("/web/chat/feishu/qrcode/status")
    public Result<Map> feishuQrcodeStatus(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        try {
            FeishuAppRegistration.PollResult pollResult = feishuQRBindManager.pollQrBinding(sessionId);

            Map<String, Object> data = new LinkedHashMap<>();

            if (pollResult.isSuccess()) {
                // 扫码成功：自动绑定会话并启动 Stream 连接
                String appId = pollResult.clientId;
                String appSecret = pollResult.clientSecret;
                String openId = pollResult.openId;

                if (openId != null) {
                    feishuLink.bindSession(sessionId, openId, appId, appSecret);
                }

                // 启动 WebSocket Stream 连接
                feishuLink.startStream(appId, appSecret, sessionId);

                data.put("status", "success");
                data.put("bound", true);
            } else if (pollResult.isWaiting()) {
                data.put("status", "waiting");
                data.put("message", pollResult.message);
            } else if (pollResult.isSlowDown()) {
                data.put("status", "waiting");
                data.put("message", pollResult.message);
            } else {
                data.put("status", "failed");
                data.put("message", pollResult.message);
            }

            return Result.succeed(data);
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "error");
            data.put("message", e.getMessage());
            return Result.succeed(data);
        }
    }

    /**
     * 取消飞书扫码绑定。
     *
     * @param sessionId 当前会话标识
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/feishu/qrcode/cancel")
    public Result feishuQrcodeCancel(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        feishuQRBindManager.cancelQrBinding(sessionId);
        return Result.succeed();
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

    // ==================== 钉钉扫码绑定接口 ====================

    /**
     * 开始钉钉扫码绑定。
     *
     * <p>通过 Device Authorization Grant 流程获取二维码 URL，
     * 用户使用钉钉 App 扫码并授权后自动完成绑定。</p>
     *
     * @param sessionId 当前会话标识
     * @return 包含 qrUrl、expiresIn、interval 的结果
     */
    @Post
    @Mapping("/web/chat/dingtalk/qrcode")
    public Result<Map> dingtalkQrcode(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        try {
            DingTalkQRBindManager.BindStartResult result = dingtalkQRBindManager.startQrBinding(sessionId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("qrUrl", result.qrUrl);
            data.put("expiresIn", result.expiresIn);
            data.put("interval", result.interval);
            data.put("sessionId", sessionId);
            return Result.succeed(data);
        } catch (Exception e) {
            return Result.failure("获取钉钉二维码失败: " + e.getMessage());
        }
    }

    /**
     * 轮询钉钉扫码绑定状态。
     *
     * <p>当用户扫码授权成功后，自动获取 AppKey 和 AppSecret，
     * 并调用钉钉通道完成会话绑定和 Stream 连接启动。</p>
     *
     * @param sessionId 当前会话标识
     * @return 包含绑定状态的结果
     */
    @Get
    @Mapping("/web/chat/dingtalk/qrcode/status")
    public Result<Map> dingtalkQrcodeStatus(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        try {
            DingTalkAppRegistration.PollResult pollResult = dingtalkQRBindManager.pollQrBinding(sessionId);

            Map<String, Object> data = new LinkedHashMap<>();

            if (pollResult.isSuccess()) {
                // 扫码成功：自动使用获取到的 AppKey + AppSecret 启动 Stream 连接
                String clientId = pollResult.clientId;
                String clientSecret = pollResult.clientSecret;

                if (clientId != null && clientSecret != null) {
                    // 启动 Stream 连接（用户向机器人发消息后会自动完成绑定）
                    dingTalkLink.startStream(clientId, clientSecret, sessionId);
                }

                data.put("status", "success");
                data.put("bound", true);
                data.put("clientId", clientId);
            } else if (pollResult.isWaiting()) {
                data.put("status", "waiting");
                data.put("message", pollResult.message);
            } else {
                data.put("status", "failed");
                data.put("message", pollResult.message);
            }

            return Result.succeed(data);
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "error");
            data.put("message", e.getMessage());
            return Result.succeed(data);
        }
    }

    /**
     * 取消钉钉扫码绑定。
     *
     * @param sessionId 当前会话标识
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/dingtalk/qrcode/cancel")
    public Result dingtalkQrcodeCancel(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        dingtalkQRBindManager.cancelQrBinding(sessionId);
        return Result.succeed();
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
