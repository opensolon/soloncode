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
package org.noear.solon.bot.core.memory.classifier;

import lombok.Getter;

/**
 * 记忆类别（内部使用，用户不可见）
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public enum MemoryCategory {
    WORKING("工作记忆", 600_000L),          // 10 分钟
    SHORT_TERM("短期记忆", 3_600_000L),    // 1 小时
    LONG_TERM("长期记忆", 7 * 24 * 3600_000L), // 7 天
    PERMANENT("永久记忆", -1L);           // 永久

    private final String displayName;
    private final long defaultTtl;

    MemoryCategory(String displayName, long defaultTtl) {
        this.displayName = displayName;
        this.defaultTtl = defaultTtl;
    }
}
