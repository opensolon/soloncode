package org.noear.solon.codecli.portal;

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
 *
 * @author noear 2026/5/12 created
 *
 */
public class WebChannel implements Runnable{

    private final WeChatLink weChatLink;
    private final FeishuLink feishuLink;
    private final DingTalkLink dingTalkLink;

    public WebChannel(HarnessEngine engine, WebGate webGate) {
        this.weChatLink = new WeChatLink(engine, webGate);
        this.feishuLink = new FeishuLink(engine, webGate);
        this.dingTalkLink = new DingTalkLink(engine, webGate);
    }

    @Override
    public void run() {
        weChatLink.run();
        feishuLink.run();
        dingTalkLink.run();
    }

    // ==================== 微信 ClawBot 通道接口 ====================

    /**
     * 获取微信扫码登录二维码
     */
    @Get
    @Mapping("/chat/wechat/qrcode")
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
     * 轮询微信扫码状态
     */
    @Get
    @Mapping("/chat/wechat/qrcode/status")
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

        // 扫码确认后自动绑定
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
     * 解绑微信通道
     */
    @Post
    @Mapping("/chat/wechat/unbind")
    public Result wechatUnbind(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        weChatLink.unbindSession(sessionId);
        return Result.succeed();
    }

    /**
     * 查询会话微信绑定状态
     */
    @Get
    @Mapping("/chat/wechat/status")
    public Result<Map> wechatStatus(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure("Invalid sessionId");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bound", weChatLink.isBound(sessionId));
        return Result.succeed(data);
    }

    /**
     * 获取 WeChatLink 实例（供 Configurator 注册启动）
     */
    public WeChatLink getWeChatLink() {
        return weChatLink;
    }

    /**
     * 获取 FeishuLink 实例
     */
    public FeishuLink getFeishuLink() {
        return feishuLink;
    }

    /**
     * 获取 DingTalkLink 实例
     */
    public DingTalkLink getDingTalkLink() {
        return dingTalkLink;
    }

    // ==================== 飞书通道接口 ====================

    /**
     * 绑定飞书到指定会话
     */
    /**
     * 绑定飞书到指定会话（提交 App ID + App Secret，启动 WebSocket 并等待自动绑定）
     */
    @Post
    @Mapping("/chat/feishu/bind")
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
     * 解绑飞书通道
     */
    @Post
    @Mapping("/chat/feishu/unbind")
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
     * 查询会话飞书绑定状态
     */
    /**
     * 查询会话飞书绑定状态（含 Stream 连接状态）
     */
    @Get
    @Mapping("/chat/feishu/status")
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

    // ==================== 钉钉通道接口 ====================

    /**
     * 绑定钉钉到指定会话（提交 AppKey + AppSecret，启动 Stream 并等待自动绑定）
     */
    @Post
    @Mapping("/chat/dingtalk/bind")
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
     * 解绑钉钉通道
     */
    @Post
    @Mapping("/chat/dingtalk/unbind")
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
     * 查询会话钉钉绑定状态（前端轮询用，返回 streamStarted/pending/bound）
     */
    @Get
    @Mapping("/chat/dingtalk/status")
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
}