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
package org.noear.solon.codecli.channel;

/**
 * IM 通道统一接口
 *
 * <p>所有 IM 通道（微信、飞书、钉钉等）均实现此接口，
 * WebStreamBuilder 通过路由表模式遍历所有已注册通道进行回复分发。</p>
 *
 * @author noear 2026/5/9 created
 */
public interface Channel {
    /**
     * 通道名称标识
     */
    String getChannelName();

    /**
     * 查询指定会话是否已绑定此通道
     */
    boolean isBound(String sessionId);

    /**
     * 向指定会话绑定的 IM 用户发送回复
     */
    void sendReply(String sessionId, String text, boolean isFinal);

    /** 使用入站消息的回复目标快照；实现方负责在目标缺失时兼容会话绑定。 */
    void sendReply(String sessionId, String text, boolean isFinal,
                   String sourceUserId, String replyTarget, String messageId);

    /**
     * 下发一条交互状态信号（非聊天内容，不进历史、不走流式）。
     *
     * <p>IM 的感知远不如 web：排队、长任务、拒收这些 web 侧的可见状态，
     * 需要主动推给来源端。默认空实现，通道按自身能力渲染或忽略。</p>
     *
     * @param status 状态类型
     * @param detail 文案；为空时通道可回退到 {@link ImMessages#textOf}
     */
    default void sendStatus(String sessionId, ImStatus status, String detail,
                            String sourceUserId, String replyTarget, String messageId) {
    }
}
