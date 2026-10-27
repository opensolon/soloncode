/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
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
package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.builtin.SteerPort;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult;

/**
 * 插话端口适配器：SteerPort 的薄委托实现，直接转调 harness 的
 * {@link SteerInterceptor#steer}（runId/steerId 由后端生成）。
 * 只做投递不做降级：失败状态码由 harness SteerCommand 如实告知用户。
 */
public class SteerPortImpl implements SteerPort {
    @Override
    public SteerResult steer(AgentSession session, String text, String source,
                             String sourceUserId, String replyTarget, String messageId) {
        return SteerInterceptor.steer(session, null, null, text, source,
                sourceUserId, replyTarget, messageId, null);
    }
}
