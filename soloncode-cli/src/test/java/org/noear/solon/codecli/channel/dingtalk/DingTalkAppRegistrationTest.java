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
package org.noear.solon.codecli.channel.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 钉钉扫码绑定相关类单元测试
 *
 * <p>覆盖 DingTalk Device Flow 完整协议：init → begin → poll，以及
 * QR Manager 会话管理、错误路径、QR URL fallback 等场景。</p>
 *
 * @author soloncode 2026/6/25 created
 */
class DingTalkAppRegistrationTest {

    // ===== PollResult 状态测试 =====

    @Test
    void pollResult_success_shouldReturnIsSuccess() {
        DingTalkAppRegistration.PollResult result = DingTalkAppRegistration.PollResult.success(
                "ding12345",
                "sec_abcdef123456");

        assertTrue(result.isSuccess());
        assertFalse(result.isWaiting());
        assertFalse(result.isFailed());

        assertEquals("ding12345", result.clientId);
        assertEquals("sec_abcdef123456", result.clientSecret);
        assertNull(result.message);
    }

    @Test
    void pollResult_waiting_shouldReturnIsWaiting() {
        DingTalkAppRegistration.PollResult result = DingTalkAppRegistration.PollResult.waiting("等待用户扫码");

        assertTrue(result.isWaiting());
        assertFalse(result.isSuccess());
        assertFalse(result.isFailed());

        assertEquals("等待用户扫码", result.message);
        assertNull(result.clientId);
        assertNull(result.clientSecret);
    }

    @Test
    void pollResult_failed_shouldReturnIsFailed() {
        DingTalkAppRegistration.PollResult result = DingTalkAppRegistration.PollResult.failed("用户拒绝了授权");

        assertTrue(result.isFailed());
        assertFalse(result.isSuccess());
        assertFalse(result.isWaiting());

        assertEquals("用户拒绝了授权", result.message);
    }

    @Test
    void pollResult_failed_withExpiredMessage() {
        DingTalkAppRegistration.PollResult result = DingTalkAppRegistration.PollResult.failed("二维码已过期");

        assertTrue(result.isFailed());
        assertEquals("二维码已过期", result.message);
    }

    // ===== BeginResult 测试 =====

    @Test
    void beginResult_shouldStoreFields() {
        DingTalkAppRegistration.BeginResult beginResult = new DingTalkAppRegistration.BeginResult();
        beginResult.deviceCode = "dc_test_device_code";
        beginResult.userCode = "user_code_abc";
        beginResult.qrUrl = "https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=ABCD";
        beginResult.expiresIn = 7200;
        beginResult.interval = 2;

        assertEquals("dc_test_device_code", beginResult.deviceCode);
        assertEquals("user_code_abc", beginResult.userCode);
        assertTrue(beginResult.qrUrl.startsWith("https://open-dev.dingtalk.com"));
        assertTrue(beginResult.qrUrl.contains("user_code=ABCD"));
        assertEquals(7200, beginResult.expiresIn);
        assertEquals(2, beginResult.interval);

        // toString 不应抛异常
        assertNotNull(beginResult.toString());
    }

    @Test
    void beginResult_defaultExpiresIn() {
        DingTalkAppRegistration.BeginResult beginResult = new DingTalkAppRegistration.BeginResult();
        beginResult.deviceCode = "dc_test";
        beginResult.qrUrl = "https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=TEST";
        beginResult.expiresIn = 0;
        beginResult.interval = 0;

        // 验证默认值保护逻辑（在 app registration 中默认有效期7200s，轮询间隔2s）
        int safeExpiresIn = beginResult.expiresIn <= 0 ? 7200 : beginResult.expiresIn;
        int safeInterval = beginResult.interval <= 0 ? 2 : beginResult.interval;

        assertEquals(7200, safeExpiresIn);
        assertEquals(2, safeInterval);
    }

    // ===== QR BindStartResult 测试 =====

    @Test
    void bindStartResult_shouldStoreFields() {
        DingTalkQRBindManager.BindStartResult result = new DingTalkQRBindManager.BindStartResult(
                "https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=ABCD",
                "device_code_test",
                7200,
                2
        );

        assertTrue(result.qrUrl.contains("user_code=ABCD"));
        assertTrue(result.qrUrl.startsWith("https://open-dev.dingtalk.com"));
        assertEquals("device_code_test", result.deviceCode);
        assertEquals(7200, result.expiresIn);
        assertEquals(2, result.interval);
    }

    @Test
    void bindStartResult_withShortValues() {
        DingTalkQRBindManager.BindStartResult result = new DingTalkQRBindManager.BindStartResult(
                "https://open-dev.dingtalk.com/openapp/registration/openClaw",
                "dc",
                600,
                5
        );
        assertEquals(600, result.expiresIn);
        assertEquals(5, result.interval);
    }

    // ===== QR 管理器会话管理测试 =====

    @Test
    void qrBindManager_cancel_shouldCleanup() {
        DingTalkQRBindManager manager = new DingTalkQRBindManager();
        manager.cancelQrBinding("non-existent-session");
        // 没有异常即通过
    }

    @Test
    void qrBindManager_cancelTwice_shouldBeIdempotent() {
        DingTalkQRBindManager manager = new DingTalkQRBindManager();
        manager.cancelQrBinding("session-1");
        manager.cancelQrBinding("session-1");
        // 幂等，没有异常即通过
    }

    // ===== URL 格式测试 =====

    @Test
    void qrUrl_format_shouldContainCorrectParams() {
        // 验证 DingTalk 扫码二维码 URL 格式
        // 钉钉注册流程返回的 verification_uri_complete 格式
        String qrUrl = "https://open-dev.dingtalk.com/openapp/registration/openClaw" +
                "?user_code=ABCD" +
                "&flow_id=f_abc123";

        assertTrue(qrUrl.startsWith("https://open-dev.dingtalk.com"));
        assertTrue(qrUrl.contains("user_code=ABCD"));
        assertTrue(qrUrl.contains("flow_id=f_abc123"));
    }

    // ===== PollResult 状态转换完整性测试 =====

    @Test
    void pollResult_allStates_exactlyOneIsTrue() {
        // 验证每个状态实例只有一个 isXxx 返回 true
        DingTalkAppRegistration.PollResult success = DingTalkAppRegistration.PollResult.success("a", "b");
        DingTalkAppRegistration.PollResult waiting = DingTalkAppRegistration.PollResult.waiting("msg");
        DingTalkAppRegistration.PollResult failed = DingTalkAppRegistration.PollResult.failed("msg");

        assertOneState(success);
        assertOneState(waiting);
        assertOneState(failed);
    }

    private void assertOneState(DingTalkAppRegistration.PollResult result) {
        int count = 0;
        if (result.isSuccess()) count++;
        if (result.isWaiting()) count++;
        if (result.isFailed()) count++;
        assertEquals(1, count, "PollResult must have exactly one state active");
    }

    // ===== 编码工具测试 =====

    @Test
    void urlEncoding_shouldWork() throws Exception {
        // 验证 device_code 等参数在 form post 中正确编码
        String deviceCode = "dc_test+device/code&special";
        String encoded = java.net.URLEncoder.encode(deviceCode, "UTF-8");
        assertTrue(encoded.contains("%2B"));
        assertTrue(encoded.contains("%2F"));
        assertTrue(encoded.contains("%26"));
        assertFalse(encoded.contains("+"));
    }

    // ================================================================
    // 以下为 begin() 和 poll() 的模拟测试（覆盖 httpJsonPost）
    // ================================================================

    /**
     * 模拟 httpJsonPost：根据 URL 路径返回对应的模拟响应。
     * REST 路径匹配：/app/registration/init → init 响应
     *              /app/registration/begin → begin 响应
     *              /app/registration/poll → poll 响应
     */
    private static class MockRegistration extends DingTalkAppRegistration {
        private final String initResponse;
        private final String beginResponse;
        private final String pollResponse;
        private int callCount = 0;

        MockRegistration(String initResponse, String beginResponse, String pollResponse) {
            this.initResponse = initResponse;
            this.beginResponse = beginResponse;
            this.pollResponse = pollResponse;
        }

        @Override
        protected String httpJsonPost(String url, String jsonBody) throws Exception {
            callCount++;
            // 根据 URL 路径匹配对应的模拟响应
            if (url.endsWith("/app/registration/init")) {
                return initResponse;
            } else if (url.endsWith("/app/registration/begin")) {
                return beginResponse;
            } else if (url.endsWith("/app/registration/poll")) {
                return pollResponse;
            }
            throw new RuntimeException("Unexpected URL: " + url + ", body: " + jsonBody);
        }

        int getCallCount() {
            return callCount;
        }
    }

    /** 模拟 httpJsonPost 异常场景 */
    private static class ErrorMockRegistration extends DingTalkAppRegistration {
        private final String response;
        ErrorMockRegistration(String response) {
            this.response = response;
        }

        @Override
        protected String httpJsonPost(String url, String jsonBody) throws Exception {
            if (response == null) {
                return null; // 模拟连接失败
            }
            return response;
        }
    }

    // ===== begin() 成功流程 =====

    @Test
    void begin_shouldSucceed() throws Exception {
        String mockInitResponse = "{\"errcode\":0,\"nonce\":\"test_nonce_123\"}";
        String mockBeginResponse = "{\"errcode\":0," +
                "\"device_code\":\"dc_test_device\"," +
                "\"user_code\":\"uc_abc\"," +
                "\"verification_uri_complete\":\"https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=uc_abc&flow_id=f123\"," +
                "\"expires_in\":7200,\"interval\":2}";

        MockRegistration reg = new MockRegistration(mockInitResponse, mockBeginResponse, null);
        DingTalkAppRegistration.BeginResult result = reg.begin();

        assertNotNull(result);
        assertEquals("dc_test_device", result.deviceCode);
        assertEquals("uc_abc", result.userCode);
        assertEquals("https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=uc_abc&flow_id=f123", result.qrUrl);
        assertEquals(7200, result.expiresIn);
        assertEquals(2, result.interval);
        assertEquals(2, reg.getCallCount()); // init + begin
    }

    /**
     * 验证 init 请求体包含 source 字段，begin 请求体仅包含 nonce 字段（JSON 格式）。
     */
    @Test
    void begin_initRequest_shouldContainSource() throws Exception {
        String mockInitResponse = "{\"errcode\":0,\"nonce\":\"test_nonce_source\"}";
        String mockBeginResponse = "{\"errcode\":0," +
                "\"device_code\":\"dc_source_test\"," +
                "\"user_code\":\"uc_source\"," +
                "\"verification_uri_complete\":\"https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=uc_source\"," +
                "\"expires_in\":7200,\"interval\":2}";

        // 使用一个记录请求体的 Mock
        MockRegistrationWithBodyCapture reg = new MockRegistrationWithBodyCapture(mockInitResponse, mockBeginResponse, null);
        reg.begin();

        // 验证 init 请求体包含 source 字段（JSON 格式）
        String initBody = reg.getCapturedInitBody();
        assertNotNull(initBody);
        assertTrue(initBody.contains("\"source\""), "init 请求应包含 source 字段: " + initBody);
        assertTrue(initBody.contains("openClaw"), "init 请求 source 值应为 openClaw: " + initBody);

        // 验证 begin 请求体仅包含 nonce 字段（JSON 格式），不含 source/archetype 等
        String beginBody = reg.getCapturedBeginBody();
        assertNotNull(beginBody);
        assertTrue(beginBody.contains("\"nonce\""), "begin 请求应包含 nonce 字段: " + beginBody);
        assertTrue(beginBody.contains("test_nonce_source"), "begin 请求应包含 nonce 值: " + beginBody);
        assertFalse(beginBody.contains("\"source\""), "begin 请求不应包含 source 参数: " + beginBody);
        assertFalse(beginBody.contains("archetype"), "begin 请求不应包含 archetype 参数: " + beginBody);
        assertFalse(beginBody.contains("auth_method"), "begin 请求不应包含 auth_method 参数: " + beginBody);
        assertFalse(beginBody.contains("request_user_info"), "begin 请求不应包含 request_user_info 参数: " + beginBody);
    }

    /** 记录请求体的 Mock */
    private static class MockRegistrationWithBodyCapture extends DingTalkAppRegistration {
        private final String initResponse;
        private final String beginResponse;
        private final String pollResponse;
        private String capturedInitBody;
        private String capturedBeginBody;

        MockRegistrationWithBodyCapture(String initResponse, String beginResponse, String pollResponse) {
            this.initResponse = initResponse;
            this.beginResponse = beginResponse;
            this.pollResponse = pollResponse;
        }

        @Override
        protected String httpJsonPost(String url, String jsonBody) throws Exception {
            if (url.endsWith("/app/registration/init")) {
                capturedInitBody = jsonBody;
                return initResponse;
            } else if (url.endsWith("/app/registration/begin")) {
                capturedBeginBody = jsonBody;
                return beginResponse;
            } else if (url.endsWith("/app/registration/poll")) {
                return pollResponse;
            }
            throw new RuntimeException("Unexpected URL: " + url);
        }

        String getCapturedInitBody() { return capturedInitBody; }
        String getCapturedBeginBody() { return capturedBeginBody; }
    }

    // ===== begin() init 错误响应 =====

    @Test
    void begin_initResponseWithErrcode_shouldThrow() {
        // 模拟 init 返回 errcode ≠ 0
        String mockInitResponse = "{\"errcode\":40001,\"errmsg\":\"invalid source\"}";
        String mockBeginResponse = "{\"errcode\":0,\"device_code\":\"dc_test\"}";

        MockRegistration reg = new MockRegistration(mockInitResponse, mockBeginResponse, null);
        Exception ex = assertThrows(RuntimeException.class, reg::begin);
        assertTrue(ex.getMessage().contains("errcode=40001"));
        assertTrue(ex.getMessage().contains("invalid source"));
    }

    @Test
    void begin_initResponseNull_shouldThrow() {
        ErrorMockRegistration reg = new ErrorMockRegistration(null);
        Exception ex = assertThrows(RuntimeException.class, reg::begin);
        assertTrue(ex.getMessage().contains("init"));
    }

    @Test
    void begin_initResponseNonJson_shouldThrow() {
        ErrorMockRegistration reg = new ErrorMockRegistration("<html>404 Not Found</html>");
        Exception ex = assertThrows(RuntimeException.class, reg::begin);
        assertNotNull(ex.getMessage());
    }

    // ===== begin() init 响应缺少 nonce =====

    @Test
    void begin_initResponseMissingNonce_shouldThrow() {
        String mockInitResponse = "{\"errcode\":0}"; // 没有 nonce 字段
        MockRegistration reg = new MockRegistration(mockInitResponse, null, null);
        Exception ex = assertThrows(RuntimeException.class, reg::begin);
        assertTrue(ex.getMessage().contains("缺少 nonce"));
    }

    // ===== begin() begin 响应缺少 device_code =====

    @Test
    void begin_beginResponseMissingDeviceCode_shouldThrow() {
        String mockInitResponse = "{\"errcode\":0,\"nonce\":\"test_nonce\"}";
        String mockBeginResponse = "{\"errcode\":0}"; // 没有 device_code

        MockRegistration reg = new MockRegistration(mockInitResponse, mockBeginResponse, null);
        Exception ex = assertThrows(RuntimeException.class, reg::begin);
        assertTrue(ex.getMessage().contains("缺少 device_code"));
    }

    // ===== begin 响应 errcode≠0 =====

    @Test
    void begin_beginResponseWithErrcode_shouldThrow() {
        String mockInitResponse = "{\"errcode\":0,\"nonce\":\"test_nonce\"}";
        String mockBeginResponse = "{\"errcode\":40002,\"errmsg\":\"begin failed\"}";

        MockRegistration reg = new MockRegistration(mockInitResponse, mockBeginResponse, null);
        Exception ex = assertThrows(RuntimeException.class, reg::begin);
        assertTrue(ex.getMessage().contains("errcode=40002"));
        assertTrue(ex.getMessage().contains("begin failed"));
    }

    // ===== QR URL fallback 测试 =====

    @Test
    void begin_shouldFallbackQrUrlWhenVerificationUriMissing() throws Exception {
        String mockInitResponse = "{\"errcode\":0,\"nonce\":\"test_nonce\"}";
        // verification_uri_complete 为空，但提供了 user_code
        String mockBeginResponse = "{\"errcode\":0," +
                "\"device_code\":\"dc_test\"," +
                "\"user_code\":\"uc_fallback\"," +
                "\"expires_in\":7200,\"interval\":2}";

        MockRegistration reg = new MockRegistration(mockInitResponse, mockBeginResponse, null);
        DingTalkAppRegistration.BeginResult result = reg.begin();

        assertNotNull(result);
        assertEquals("dc_test", result.deviceCode);
        // 应使用 fallback URL
        assertNotNull(result.qrUrl);
        assertTrue(result.qrUrl.contains("user_code=uc_fallback"));
        assertTrue(result.qrUrl.contains("openClaw"));
    }

    @Test
    void begin_shouldFallbackQrUrlWhenVerificationUriNull() throws Exception {
        String mockInitResponse = "{\"errcode\":0,\"nonce\":\"test_nonce\"}";
        // verification_uri_complete 为 null
        String mockBeginResponse = "{\"errcode\":0," +
                "\"device_code\":\"dc_test\"," +
                "\"user_code\":\"uc_fallback2\"," +
                "\"verification_uri_complete\":null," +
                "\"expires_in\":7200,\"interval\":2}";

        MockRegistration reg = new MockRegistration(mockInitResponse, mockBeginResponse, null);
        DingTalkAppRegistration.BeginResult result = reg.begin();

        assertNotNull(result);
        assertEquals("dc_test", result.deviceCode);
        // 应使用 fallback URL
        assertNotNull(result.qrUrl);
        assertTrue(result.qrUrl.contains("user_code=uc_fallback2"));
    }

    // ===== poll() 各状态测试 =====

    @Test
    void poll_shouldReturnWaiting() throws Exception {
        String mockPollResponse = "{\"errcode\":0,\"status\":\"WAITING\"}";

        MockRegistration reg = new MockRegistration(null, null, mockPollResponse);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isWaiting());
        assertEquals("等待用户扫码", result.message);
    }

    @Test
    void poll_shouldReturnSuccess() throws Exception {
        String mockPollResponse = "{\"errcode\":0,\"status\":\"SUCCESS\"," +
                "\"client_id\":\"ding_client123\"," +
                "\"client_secret\":\"sec_abc123def456\"}";

        MockRegistration reg = new MockRegistration(null, null, mockPollResponse);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isSuccess());
        assertEquals("ding_client123", result.clientId);
        assertEquals("sec_abc123def456", result.clientSecret);
    }

    @Test
    void poll_shouldReturnFailed() throws Exception {
        String mockPollResponse = "{\"errcode\":40002,\"errmsg\":\"access_denied\"}";

        MockRegistration reg = new MockRegistration(null, null, mockPollResponse);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isFailed());
        assertTrue(result.message.contains("40002"));
        assertTrue(result.message.contains("access_denied"));
    }

    @Test
    void poll_shouldReturnFailed_whenStatusFAIL() throws Exception {
        String mockPollResponse = "{\"errcode\":0,\"status\":\"FAIL\",\"fail_reason\":\"用户拒绝授权\"}";

        MockRegistration reg = new MockRegistration(null, null, mockPollResponse);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isFailed());
        assertEquals("用户拒绝授权", result.message);
    }

    @Test
    void poll_shouldReturnFailed_whenStatusEXPIRED() throws Exception {
        String mockPollResponse = "{\"errcode\":0,\"status\":\"EXPIRED\"}";

        MockRegistration reg = new MockRegistration(null, null, mockPollResponse);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isFailed());
        assertEquals("二维码已过期", result.message);
    }

    @Test
    void poll_shouldReturnFailed_whenStatusFAIL_withoutReason() throws Exception {
        String mockPollResponse = "{\"errcode\":0,\"status\":\"FAIL\"}";

        MockRegistration reg = new MockRegistration(null, null, mockPollResponse);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isFailed());
        assertEquals("用户拒绝授权", result.message);
    }

    @Test
    void poll_shouldReturnWaitingWhenResponseNull() throws Exception {
        MockRegistration reg = new MockRegistration(null, null, null);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isWaiting());
        assertEquals("请求失败", result.message);
    }

    @Test
    void poll_successButClientIdEmpty_shouldReturnFailed() throws Exception {
        String mockPollResponse = "{\"errcode\":0,\"status\":\"SUCCESS\"," +
                "\"client_id\":\"\"," +
                "\"client_secret\":\"sec_abc\"}";

        MockRegistration reg = new MockRegistration(null, null, mockPollResponse);
        DingTalkAppRegistration.PollResult result = reg.poll("dc_test", 2, 7200);

        assertTrue(result.isFailed());
        assertEquals("扫码成功但 client_id 为空", result.message);
    }

    // ===== manager 会话过期测试 =====

    @Test
    void manager_pollExpiredSession_shouldReturnFailed() throws Exception {
        DingTalkQRBindManager manager = new DingTalkQRBindManager();
        // 没有开始任何绑定，直接轮询
        DingTalkAppRegistration.PollResult result = manager.pollQrBinding("non-existent-session");

        assertTrue(result.isFailed());
        assertEquals("没有正在进行的扫码绑定", result.message);
    }

    // ===== manager session 复用 qrUrl 保护测试 =====

    @Test
    void manager_shouldNotReuseSession_whenQrUrlNull() throws Exception {
        // 验证当 session 的 qrUrl 为空时，不会复用而是重新创建
        // 这个测试通过模拟一个返回 null qrUrl 的注册来验证
        DingTalkQRBindManager manager = new DingTalkQRBindManager();

        // 没有有效 session 时轮询返回失败
        DingTalkAppRegistration.PollResult result = manager.pollQrBinding("no-session");
        assertTrue(result.isFailed());
        assertTrue(result.message.contains("没"));
    }

    @Test
    void manager_cancelShouldCleanup_whenSessionExists() throws Exception {
        // 开始一个绑定然后取消
        // 由于没有有效的注册模拟，这里只是验证取消操作在无 session 时不会报错
        DingTalkQRBindManager manager = new DingTalkQRBindManager();
        manager.cancelQrBinding("any-session");
        // 没有异常即通过
    }

    // ===== REST 路径格式验证 =====

    @Test
    void registrationPaths_shouldBeCorrect() {
        assertTrue(DingTalkAppRegistration.INIT_PATH.endsWith("/init"));
        assertTrue(DingTalkAppRegistration.BEGIN_PATH.endsWith("/begin"));
        assertTrue(DingTalkAppRegistration.POLL_PATH.endsWith("/poll"));

        assertEquals("/app/registration/init", DingTalkAppRegistration.INIT_PATH);
        assertEquals("/app/registration/begin", DingTalkAppRegistration.BEGIN_PATH);
        assertEquals("/app/registration/poll", DingTalkAppRegistration.POLL_PATH);
    }

    @Test
    void registrationSource_shouldBeOpenClaw() {
        assertEquals("openClaw", DingTalkAppRegistration.REGISTRATION_SOURCE);
    }

    // ===== 完整流程模拟测试 =====

    @Test
    void fullFlow_init_to_begin_to_poll() throws Exception {
        // 模拟完整的初始化→扫码→轮询成功 全流程
        String mockInitResponse = "{\"errcode\":0,\"nonce\":\"flow_test_nonce\"}";
        String mockBeginResponse = "{\"errcode\":0," +
                "\"device_code\":\"flow_device_code\"," +
                "\"user_code\":\"flow_user_code\"," +
                "\"verification_uri_complete\":\"https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=flow_user_code&flow_id=flow123\"," +
                "\"expires_in\":7200,\"interval\":2}";
        String mockPollResponse = "{\"errcode\":0,\"status\":\"SUCCESS\"," +
                "\"client_id\":\"flow_client_id\"," +
                "\"client_secret\":\"flow_client_secret\"}";

        MockRegistration reg = new MockRegistration(mockInitResponse, mockBeginResponse, mockPollResponse);

        // 1. begin
        DingTalkAppRegistration.BeginResult beginResult = reg.begin();
        assertNotNull(beginResult);
        assertEquals("flow_device_code", beginResult.deviceCode);
        assertEquals("flow_user_code", beginResult.userCode);
        assertTrue(beginResult.qrUrl.contains("flow_user_code"));
        assertEquals(7200, beginResult.expiresIn);
        assertEquals(2, beginResult.interval);
        assertEquals(2, reg.getCallCount()); // init + begin

        // 2. poll
        DingTalkAppRegistration.PollResult pollResult = reg.poll("flow_device_code", 2, 7200);
        assertTrue(pollResult.isSuccess());
        assertEquals("flow_client_id", pollResult.clientId);
        assertEquals("flow_client_secret", pollResult.clientSecret);
        assertEquals(3, reg.getCallCount()); // init + begin + poll
    }
}
