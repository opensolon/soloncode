package org.noear.solon.codecli.channel;

import org.noear.solon.codecli.channel.dingtalk.DingTalkLink;
import org.noear.solon.codecli.channel.dingtalk.DingTalkQRBindManager;
import org.noear.solon.codecli.channel.feishu.FeishuLink;
import org.noear.solon.codecli.channel.feishu.FeishuQRBindManager;
import org.noear.solon.codecli.channel.wechat.WeChatLink;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.Lifecycle;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author noear 2026/8/19 created
 *
 */
public class ChannelHub implements Lifecycle {
    private final List<Channel> imLinks;

    /**
     * 微信通道适配器，负责扫码登录、会话绑定与消息转发
     */
    private final WeChatLink weChatLink;

    /**
     * 飞书通道适配器，负责 WebSocket Stream 连接、会话绑定与消息转发
     */
    private final FeishuLink feishuLink;

    /**
     * 钉钉通道适配器，负责 Stream 连接、会话绑定与消息转发
     */
    private final DingTalkLink dingTalkLink;

    /**
     * 飞书扫码绑定管理器
     */
    private final FeishuQRBindManager feishuQRBindManager;

    /**
     * 钉钉扫码绑定管理器
     */
    private final DingTalkQRBindManager dingtalkQRBindManager;

    public WeChatLink getWeChatLink() {
        return weChatLink;
    }

    public FeishuLink getFeishuLink() {
        return feishuLink;
    }

    public FeishuQRBindManager getFeishuQRBindManager() {
        return feishuQRBindManager;
    }

    public DingTalkLink getDingTalkLink() {
        return dingTalkLink;
    }

    public DingTalkQRBindManager getDingtalkQRBindManager() {
        return dingtalkQRBindManager;
    }

    /**
     * 构造函数：初始化三个通道适配器和扫码绑定管理器。
     */
    public ChannelHub(WorkspaceContext wsContext) {
        this.weChatLink = new WeChatLink(wsContext);
        this.feishuLink = new FeishuLink(wsContext);
        this.dingTalkLink = new DingTalkLink(wsContext);
        this.feishuQRBindManager = new FeishuQRBindManager();
        this.dingtalkQRBindManager = new DingTalkQRBindManager();

        this.imLinks = Arrays.asList(weChatLink, feishuLink, dingTalkLink);
    }

    public List<Channel> getImLinks() {
        return imLinks;
    }

    @Override
    public void start() {
        weChatLink.run();
        feishuLink.run();
        dingTalkLink.run();
    }

    /**
     * 停止全部 IM 渠道长连接（工作区关闭/LRU 回收时调用）。
     * 各 Link 内部有幂等保护，重复调用安全。
     */
    @Override
    public void stop() {
        weChatLink.stop();
        feishuLink.stop();
        dingTalkLink.stop();
    }
}
