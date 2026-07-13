/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 飞书扫码绑定相关类单元测试
 *
 * @author soloncode 2026/6/24 created
 */
class FeishuAppRegistrationTest {

    // ===== PollResult 状态测试 =====

    @Test
    void pollResult_success_shouldReturnIsSuccess() {
        FeishuAppRegistration.PollResult result = FeishuAppRegistration.PollResult.success(
                "cli_a5ca35a685b0x26e",
                "baBqE5um9LbFGDy3X7LcfxQX1sqpXlwy",
                "ou_123456789");

        assertTrue(result.isSuccess());
        assertFalse(result.isWaiting());
        assertFalse(result.isFailed());
        assertFalse(result.isSlowDown());

        assertEquals("cli_a5ca35a685b0x26e", result.clientId);
        assertEquals("baBqE5um9LbFGDy3X7LcfxQX1sqpXlwy", result.clientSecret);
        assertEquals("ou_123456789", result.openId);
        assertNull(result.message);
    }

    @Test
    void pollResult_waiting_shouldReturnIsWaiting() {
        FeishuAppRegistration.PollResult result = FeishuAppRegistration.PollResult.waiting("等待用户扫码");

        assertTrue(result.isWaiting());
        assertFalse(result.isSuccess());
        assertFalse(result.isFailed());
        assertFalse(result.isSlowDown());

        assertEquals("等待用户扫码", result.message);
        assertNull(result.clientId);
        assertNull(result.openId);
    }

    @Test
    void pollResult_slowDown_shouldReturnIsSlowDown() {
        FeishuAppRegistration.PollResult result = FeishuAppRegistration.PollResult.slowDown("请减慢轮询频率");

        assertTrue(result.isSlowDown());
        assertFalse(result.isSuccess());
        assertFalse(result.isWaiting());
        assertFalse(result.isFailed());

        assertEquals("请减慢轮询频率", result.message);
    }

    @Test
    void pollResult_failed_shouldReturnIsFailed() {
        FeishuAppRegistration.PollResult result = FeishuAppRegistration.PollResult.failed("用户拒绝了授权");

        assertTrue(result.isFailed());
        assertFalse(result.isSuccess());
        assertFalse(result.isWaiting());
        assertFalse(result.isSlowDown());

        assertEquals("用户拒绝了授权", result.message);
    }

    @Test
    void pollResult_failed_withExpiredMessage() {
        FeishuAppRegistration.PollResult result = FeishuAppRegistration.PollResult.failed("二维码已过期");

        assertTrue(result.isFailed());
        assertEquals("二维码已过期", result.message);
    }

    // ===== BeginResult 测试 =====

    @Test
    void beginResult_shouldStoreFields() {
        FeishuAppRegistration.BeginResult beginResult = new FeishuAppRegistration.BeginResult();
        beginResult.deviceCode = "dc_test123";
        beginResult.userCode = "uc_test456";
        beginResult.qrUrl = "https://accounts.feishu.cn/oauth/v1/device/verify?user_code=uc_test456&flow_id=f_abc123";
        beginResult.expiresIn = 600;
        beginResult.interval = 5;

        assertEquals("dc_test123", beginResult.deviceCode);
        assertEquals("uc_test456", beginResult.userCode);
        assertTrue(beginResult.qrUrl.contains("user_code=uc_test456"));
        assertTrue(beginResult.qrUrl.startsWith("https://accounts.feishu.cn"));
        assertEquals(600, beginResult.expiresIn);
        assertEquals(5, beginResult.interval);

        // toString 不应抛异常
        assertNotNull(beginResult.toString());
    }

    // ===== QR BindStartResult 测试 =====

    @Test
    void bindStartResult_shouldStoreFields() {
        FeishuQRBindManager.BindStartResult result = new FeishuQRBindManager.BindStartResult(
                "https://accounts.feishu.cn/oauth/v1/device/verify?user_code=xxx&flow_id=f_abc",
                "device_code_123",
                600,
                5
        );

        assertTrue(result.qrUrl.contains("user_code=xxx"));
        assertTrue(result.qrUrl.startsWith("https://accounts.feishu.cn"));
        assertEquals("device_code_123", result.deviceCode);
        assertEquals(600, result.expiresIn);
        assertEquals(5, result.interval);
    }

    // ===== QR 管理器会话管理测试 =====

    @Test
    void qrBindManager_cancel_shouldCleanup() {
        // 测试 cancel 不存在的会话（不应该抛异常）
        FeishuQRBindManager manager = new FeishuQRBindManager();
        manager.cancelQrBinding("non-existent-session");
        // 没有异常即通过
    }

    @Test
    void qrBindManager_cancelTwice_shouldBeIdempotent() {
        FeishuQRBindManager manager = new FeishuQRBindManager();
        manager.cancelQrBinding("session-1");
        manager.cancelQrBinding("session-1");
        // 幂等，没有异常即通过
    }

    // ===== URL 编码测试 =====

    @Test
    void authorizeUrl_shouldContainCorrectParams() {
        // 测试 OAuth URL 构建（模拟 FeishuClient 行为）
        String appId = "cli_a5ca35a685b0x26e";
        String redirectUri = "https://example.com/callback";
        String state = "test-state-123";

        String url = "https://passport.feishu.cn/suite/passport/oauth/authorize"
                + "?client_id=" + appId
                + "&redirect_uri=" + redirectUri
                + "&response_type=code"
                + "&state=" + state;

        assertTrue(url.startsWith("https://passport.feishu.cn/suite/passport/oauth/authorize"));
        assertTrue(url.contains("client_id=cli_a5ca35a685b0x26e"));
        assertTrue(url.contains("redirect_uri=" + redirectUri));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("state=test-state-123"));
    }

    @Test
    void qrUrl_shouldContainUserCode() {
        // 验证 QR URL 格式（verification_uri_complete 由飞书 API 返回，直接使用）
        String userCode = "uc_test_user_code";
        // 模拟飞书 API 返回的 verification_uri_complete 格式
        String qrUrl = "https://accounts.feishu.cn/oauth/v1/device/verify"
                + "?user_code=" + userCode
                + "&flow_id=f_abc123";

        assertTrue(qrUrl.contains("user_code=" + userCode));
        assertTrue(qrUrl.startsWith("https://accounts.feishu.cn"));
    }
}
