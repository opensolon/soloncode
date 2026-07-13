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
package org.noear.solon.codecli.channel.feishu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书扫码绑定管理器
 *
 * <p>通过 Device Authorization Grant (RFC 8628) 流程实现扫码绑定。
 * 利用 {@link FeishuAppRegistration} 获取二维码，用户扫码授权后自动获得
 * App ID、App Secret 和 openId，完成会话绑定。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   // 1. 开始绑定
 *   BindStartResult result = manager.startQrBinding(sessionId);
 *
 *   // 2. 前端展示 result.qrUrl 的二维码
 *
 *   // 3. 前端定期轮询
 *   PollResult pollResult = manager.pollQrBinding(sessionId);
 *   if (pollResult.isSuccess()) {
 *       // 绑定成功，用 pollResult.clientId/secret/openId 调用 feishuLink.bindSession()
 *   }
 * </pre>
 *
 * @author soloncode 2026/6/24 created
 */
public class FeishuQRBindManager {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuQRBindManager.class);

    /**
     * sessionId -> QrBindSession
     */
    private final Map<String, QrBindSession> sessions = new ConcurrentHashMap<>();

    public FeishuQRBindManager() {
    }

    /**
     * 开始扫码绑定流程
     *
     * @param sessionId 会话 ID
     * @return BindStartResult 包含二维码 URL 等信息
     * @throws Exception 请求失败
     */
    public BindStartResult startQrBinding(String sessionId) throws Exception {
        QrBindSession existing = sessions.get(sessionId);
        if (existing != null && !existing.isExpired()) {
            // 已有活跃的绑定会话且未过期，复用
            return new BindStartResult(existing.qrUrl,
                    existing.deviceCode,
                    existing.expiresIn,
                    existing.interval);
        }

        FeishuAppRegistration registration = new FeishuAppRegistration();
        FeishuAppRegistration.BeginResult beginResult = registration.begin();

        QrBindSession qrSession = new QrBindSession();
        qrSession.deviceCode = beginResult.deviceCode;
        qrSession.qrUrl = beginResult.qrUrl;
        qrSession.expiresIn = beginResult.expiresIn;
        qrSession.interval = beginResult.interval;
        qrSession.registration = registration;

        sessions.put(sessionId, qrSession);

        LOG.info("[FeishuQR] Started QR binding for session {}, deviceCode={}",
                sessionId,
                beginResult.deviceCode.substring(0, Math.min(8, beginResult.deviceCode.length())) + "...");

        return new BindStartResult(beginResult.qrUrl,
                beginResult.deviceCode,
                beginResult.expiresIn,
                beginResult.interval);
    }

    /**
     * 轮询扫码绑定状态
     *
     * @param sessionId 会话 ID
     * @return PollResult 包含状态
     * @throws Exception 请求失败
     */
    public FeishuAppRegistration.PollResult pollQrBinding(String sessionId) throws Exception {
        QrBindSession qrSession = sessions.get(sessionId);
        if (qrSession == null) {
            return FeishuAppRegistration.PollResult.failed("没有正在进行的扫码绑定");
        }

        if (qrSession.isExpired()) {
            sessions.remove(sessionId);
            return FeishuAppRegistration.PollResult.failed("二维码已过期");
        }

        FeishuAppRegistration.PollResult result = qrSession.registration.poll(
                qrSession.deviceCode,
                qrSession.interval,
                qrSession.expiresIn);

        if (result.isSuccess()) {
            LOG.info("[FeishuQR] QR binding success for session {}, openId={}",
                    sessionId, result.openId);
            // 成功后清理会话
            sessions.remove(sessionId);
        } else if (result.isFailed()) {
            sessions.remove(sessionId);
        }

        return result;
    }

    /**
     * 取消扫码绑定
     *
     * @param sessionId 会话 ID
     */
    public void cancelQrBinding(String sessionId) {
        QrBindSession removed = sessions.remove(sessionId);
        if (removed != null) {
            LOG.info("[FeishuQR] Cancelled QR binding for session {}", sessionId);
        }
    }

    /**
     * 二维码绑定会话内部状态
     */
    private static class QrBindSession {
        final long startTimeMs = System.currentTimeMillis();
        String deviceCode;
        String qrUrl;
        int expiresIn;
        int interval;
        FeishuAppRegistration registration;

        boolean isExpired() {
            return (System.currentTimeMillis() - startTimeMs) > (expiresIn * 1000L);
        }
    }

    /**
     * 开始绑定的返回结果
     */
    public static class BindStartResult {
        /** 二维码页面 URL（前端可据此生成二维码） */
        public final String qrUrl;
        /** 设备码（用于后续轮询） */
        public final String deviceCode;
        /** 过期时间（秒） */
        public final int expiresIn;
        /** 轮询间隔（秒） */
        public final int interval;

        public BindStartResult(String qrUrl, String deviceCode, int expiresIn, int interval) {
            this.qrUrl = qrUrl;
            this.deviceCode = deviceCode;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }
}
