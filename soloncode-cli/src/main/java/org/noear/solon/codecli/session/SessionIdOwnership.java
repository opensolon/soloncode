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
package org.noear.solon.codecli.session;

/**
 * 会话归属判定 —— 三种运行时入口（CLI / Web / 桌面）的 ID 形态守门人。
 *
 * <p>背景：goal 与 loop 的执行通道都是「认领式」的（{@code GoalExecutor} 返回 null 表示
 * 本入口不认领，引擎按注册顺序取首个认领者），而会话 ID 的归属约定只存在于宿主。
 * 此前三个入口各自在注册点内联写谓词（{@code equals("cli")} / {@code startsWith("web-")} /
 * {@code matches("[0-9]{1,18}")}），彼此看不到对方，一旦有人改宽边界就会静默抢跑别人的会话
 * ——表现为「桌面端点了触发，结果 Web 侧跑了一轮」，且没有任何报错。</p>
 *
 * <p>把这些谓词收拢到一处，是为了让「谁拥有这个 sessionId」成为可审视的单一事实：</p>
 * <ul>
 *   <li>CLI：固定单会话 {@code cli}；</li>
 *   <li>Web：{@code web-*} 用户会话与 {@code auto-*} 自动化会话；</li>
 *   <li>桌面：纯数字主键（1~18 位，防路径穿越，与 {@code DesktopSessionIds} 同口径）。</li>
 * </ul>
 *
 * <p>新增入口（第四种形态）时，只需在此加一个谓词并在注册点复用它，
 * 不要在内联位置写新的正则。</p>
 *
 * @author bai
 */
public final class SessionIdOwnership {
    /** CLI 会话 ID（进程内单会话）。 */
    public static final String CLI_SESSION_ID = "cli";
    private static final String WEB_PREFIX = "web-";
    private static final String AUTO_PREFIX = "auto-";

    private SessionIdOwnership() {
    }

    /** 是否 CLI 会话。 */
    public static boolean isCli(String sessionId) {
        return CLI_SESSION_ID.equals(sessionId);
    }

    /** 是否 Web 会话（用户会话或自动化会话）。 */
    public static boolean isWeb(String sessionId) {
        return sessionId != null
                && (sessionId.startsWith(WEB_PREFIX) || sessionId.startsWith(AUTO_PREFIX));
    }

    /** 是否桌面会话（纯数字主键）。 */
    public static boolean isDesktop(String sessionId) {
        return sessionId != null && sessionId.matches("[0-9]{1,18}");
    }
}
