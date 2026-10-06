/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel;

/**
 * 微信通道的工作区级薄外观：所有绑定与连接归属都在进程级 {@link ImGateway}。
 *
 * <p>保留与 {@code WeChatLink} 同名的方法，使 {@code ChannelHub} / {@code WebChannel} /
 * {@code WebStreamBuilder} 的调用点无需感知进程级改造；{@code stop()} 为空实现，
 * 这是「工作区 LRU 回收不再断开微信长轮询」的落点。</p>
 *
 * @author noear
 */
public class WeChatGatewayLink implements Channel {
    private final ImGateway gateway;
    private final String workspaceId;

    public WeChatGatewayLink(ImGateway gateway, String workspaceId) {
        this.gateway = gateway;
        this.workspaceId = workspaceId;
    }

    /** 绑定微信到指定会话（幂等；重复确认不会重置游标）。 */
    public void bindSession(String sessionId, String botToken, String ilinkBotId,
                            String ilinkUserId, String baseUrl) {
        gateway.adoptWeChat(ilinkUserId, botToken, ilinkBotId, baseUrl, workspaceId, sessionId, false);
    }

    /** 解绑微信。 */
    public void unbindSession(String sessionId) {
        gateway.removeWeChat(workspaceId, sessionId);
    }

    /** 拉起进程级微信传输层（幂等；由 ChannelHub.start() 调用）。 */
    public void run() {
        gateway.startWeChat();
    }

    /** 工作区关闭时不停止进程级连接。 */
    public void stop() {
        // 故意为空：微信连接归属进程，工作区 LRU 回收/关闭不断连
    }

    @Override
    public String getChannelName() {
        return "wechat";
    }

    /** 会话维度的微信绑定状态（本地 / 异地 / 未绑定）。 */
    public ImGateway.WeChatStatus status(String sessionId) {
        return gateway.wechatStatus(workspaceId, sessionId);
    }

    @Override
    public boolean isBound(String sessionId) {
        return gateway.isWeChatBound(workspaceId, sessionId);
    }

    @Override
    public void sendReply(String sessionId, String text, boolean isFinal) {
        gateway.sendWeChatReply(workspaceId, sessionId, text, isFinal, null, null, null);
    }

    @Override
    public void sendReply(String sessionId, String text, boolean isFinal,
                          String sourceUserId, String replyTarget, String messageId) {
        gateway.sendWeChatReply(workspaceId, sessionId, text, isFinal, sourceUserId, replyTarget, messageId);
    }

    @Override
    public void sendStatus(String sessionId, ImStatus status, String detail,
                           String sourceUserId, String replyTarget, String messageId) {
        gateway.sendWeChatStatus(workspaceId, sessionId, status, detail, sourceUserId, replyTarget, messageId);
    }
}
