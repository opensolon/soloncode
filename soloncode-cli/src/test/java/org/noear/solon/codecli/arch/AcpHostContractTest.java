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
package org.noear.solon.codecli.arch;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.codecli.entry.acp.AcpLink;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACP 入口走 L0（{@code HarnessHost}）：受理、开流、终态、停流都由骨架代办。
 *
 * <p>迁移前 ACP 是唯一直连 {@code engine.handle} 的入口，自己用 {@code takeWhile(!cancelled)} 停流、
 * 自己按位置撤回积压条目、取消只写一个布尔标记——没有领取运行凭据、没有取消屏障、不落取消消息，
 * 与 CLI / Web / 桌面端的「停止就是停止」语义不同。这里锁定它不再回到那种写法。</p>
 *
 * <p><b>断言方式</b>：能用反射表达的（结构、依赖类型、有没有那个取消标记）一律用反射——
 * 它不随换行、抽方法、改局部变量名而失效；只有「必须调到哪个方法」这类无法从类型上读出的约定，
 * 才退化为去注释后的源码关键字匹配。</p>
 */
class AcpHostContractTest {
    private static final Path ACP = Paths.get("src/main/java/org/noear/solon/codecli/entry/acp/AcpLink.java");

    /** 骨架装配：字段类型就是「这一轮归框架编排」的硬证据，改不了名也换不了实现 */
    @Test
    void acpHoldsAHarnessHost() throws Exception {
        Field field = AcpLink.class.getDeclaredField("host");
        assertTrue(HarnessHost.class.isAssignableFrom(field.getType()),
                "ACP 应持有 HarnessHost（受理 / 开流 / 终态 / 停流由骨架代办）");
    }

    /** 取消不再靠宿主自建标记位：有它就说明又回到「自己停流」 */
    @Test
    void sessionContextCarriesNoCancelFlag() {
        List<String> names = new ArrayList<>();
        for (Field field : AcpLink.AcpSessionContext.class.getDeclaredFields()) {
            names.add(field.getName());
        }
        assertTrue(names.contains("cwd"), "会话上下文应仍只承载工作区等信息：实际字段 " + names);
        assertTrue(names.stream().noneMatch(n -> n.toLowerCase().contains("cancel")),
                "取消经 SessionAdmin 停流，不再在会话上下文里自建标记位：实际字段 " + names);
    }

    /** 一轮必须交给 host.stream 开流、忙时必须拒绝（一问一答没有接续发） */
    @Test
    void acpGoesThroughHostStream() throws IOException {
        String code = stripComments(new String(Files.readAllBytes(ACP), StandardCharsets.UTF_8));

        assertTrue(code.contains("host.stream("), "一轮应交给 host.stream 开流");
        assertTrue(code.contains("admin(TENANT).interrupt("), "取消 / 关闭会话应经 SessionAdmin 停流");
        assertTrue(code.contains("whenBusy(BusyPolicy.REJECT)"),
                "一问一答的入口不能让输入进积压：请求返回后没人接续发");
        assertTrue(code.contains("agentRuntime.handle(") == false, "受理与结局分派应在骨架里");
        assertTrue(code.contains("takeWhile(") == false, "停止由骨架的取消屏障完成");
    }

    /** 去掉 // 行注释与 / * * / 块注释：注释里写「不要这么做」不该被判成违规 */
    private static String stripComments(String source) {
        String noBlock = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll("");
        return Pattern.compile("//[^\\n]*").matcher(noBlock).replaceAll("");
    }
}
