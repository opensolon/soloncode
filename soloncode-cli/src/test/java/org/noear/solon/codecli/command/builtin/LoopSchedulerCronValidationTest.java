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
package org.noear.solon.codecli.command.builtin;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cron 可触发性校验单元测试。
 *
 * <p>{@code requireNextFireTime} 是 loop 任务创建（模型侧 loop_add、/loop cron:、Web 端）与
 * 注册（restore/update/toggle）共用的唯一校验入口，此处钉住它对四类输入的判定与文案。
 *
 * @since 3.9.4
 */
class LoopSchedulerCronValidationTest {

    @Test
    void sevenFieldFutureCronReturnsThatMoment() {
        LocalDateTime future = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        String cron = String.format("0 %d %d %d %d ? %d",
                future.getMinute(), future.getHour(),
                future.getDayOfMonth(), future.getMonthValue(), future.getYear());

        Date next = LoopScheduler.requireNextFireTime(cron);

        assertNotNull(next);
        assertEquals(future, LocalDateTime.ofInstant(next.toInstant(), ZoneId.systemDefault()));
    }

    /** 6 位（省略年份）同样被解析器接受 —— 错误文案里的「6 或 7 位」据此而写 */
    @Test
    void sixFieldCronIsAccepted() {
        assertNotNull(LoopScheduler.requireNextFireTime("0 0 18 * * ?"));
    }

    @Test
    void expiredYearHasNoFireTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopScheduler.requireNextFireTime("0 0 18 5 9 ? 2020"));

        assertTrue(e.getMessage().contains("无有效触发时刻"), e.getMessage());
    }

    /** 语法合法但日期永不存在（2 月 31 日）：同样没有触发时刻，不能当成语法错误 */
    @Test
    void nonExistentDateHasNoFireTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopScheduler.requireNextFireTime("0 0 18 31 2 ? 2030"));

        assertTrue(e.getMessage().contains("无有效触发时刻"), e.getMessage());
    }

    @Test
    void garbageExpressionReportsParseFailure() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopScheduler.requireNextFireTime("every day at 6"));

        assertTrue(e.getMessage().contains("无法解析"), e.getMessage());
    }

    /** 日与周同时给出具体值（都不是 ?）属非法 —— 这是 LLM 手写 7 位 cron 的高频错点 */
    @Test
    void dayAndWeekBothSpecifiedReportsParseFailure() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopScheduler.requireNextFireTime("0 0 18 * * * *"));

        assertTrue(e.getMessage().contains("无法解析"), e.getMessage());
    }
}
