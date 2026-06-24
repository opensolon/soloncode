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
import static org.junit.jupiter.api.Assertions.*;

/**
 * 指纹计算单元测试（Sprint 2: 无进展检测精度改进）
 *
 * @since 3.9.3
 */
class LoopSchedulerFingerprintTest {

    // ===== 辅助：Java 8 兼容的 repeat =====

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    // ===== countLines 单元测试 =====

    @Test
    void countLinesEmptyTextReturns1() {
        assertEquals(1, LoopScheduler.countLines(""));
    }

    @Test
    void countLinesSingleLineReturns1() {
        assertEquals(1, LoopScheduler.countLines("hello world"));
    }

    @Test
    void countLinesMultipleLines() {
        assertEquals(3, LoopScheduler.countLines("line1\nline2\nline3"));
    }

    @Test
    void countLinesTrailingNewline() {
        assertEquals(2, LoopScheduler.countLines("line1\n"));
    }

    // ===== computeFingerprint（静态方法，无需 LoopScheduler 实例） =====

    @Test
    void fingerprintNullResultReturnsNull() {
        assertEquals("null", LoopScheduler.computeFingerprint(null));
    }

    @Test
    void fingerprintEmptyTextReturnsEmpty() {
        LoopExecutionResult empty = LoopExecutionResult.fromText("");
        assertEquals("empty", LoopScheduler.computeFingerprint(empty));
    }

    @Test
    void fingerprintFormatHasThreeDimensions() {
        // 200 字以内 → lenBucket=0, 1 行 → lineBucket=0, 无工具调用 → "0"
        LoopExecutionResult shortText = LoopExecutionResult.fromText("short reply");
        String fp = LoopScheduler.computeFingerprint(shortText);
        assertTrue(fp.matches("\\d+:\\d+:\\d+"),
                "fingerprint should have 3 colon-separated dimensions, got: " + fp);
    }

    // ===== 指纹区分力验证 =====

    @Test
    void fingerprintDistinguishesToolCallPresence() {
        // 短文本（<200字），无工具调用
        LoopExecutionResult noTool = LoopExecutionResult.fromText("analysis result");
        // 长文本（>20字自动判为 hasToolCalls=true），有工具调用
        LoopExecutionResult withTool = LoopExecutionResult.fromText(
                repeat("x", 21) + "\ncommand output");

        String fpNoTool = LoopScheduler.computeFingerprint(noTool);
        String fpWithTool = LoopScheduler.computeFingerprint(withTool);

        // 工具维度不同（0 vs 1）
        assertNotEquals(fpNoTool.charAt(0), fpWithTool.charAt(0),
                "tool call dimension should differ");
    }

    @Test
    void fingerprintDistinguishesDifferentLengthBuckets() {
        // 199 字 → lenBucket=0
        LoopExecutionResult shortText = LoopExecutionResult.fromText(repeat("x", 199));
        // 201 字 → lenBucket=1（不同桶）
        LoopExecutionResult longerText = LoopExecutionResult.fromText(repeat("x", 201));

        String fpShort = LoopScheduler.computeFingerprint(shortText);
        String fpLonger = LoopScheduler.computeFingerprint(longerText);

        // 200 字/桶，199 和 201 在不同桶
        assertNotEquals(fpShort, fpLonger,
                "texts of 199 and 201 chars should be in different length buckets");
    }

    @Test
    void fingerprintDistinguishesDifferentLineBuckets() {
        // 9 行 + padding ≈ 200 字 → lineBucket=0
        String text9Lines = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9";
        text9Lines = text9Lines + repeat(" ", Math.max(0, 200 - text9Lines.length()));

        // 11 行 + padding ≈ 200 字 → lineBucket=1
        String text11Lines = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\nline11";
        text11Lines = text11Lines + repeat(" ", Math.max(0, 200 - text11Lines.length()));

        LoopExecutionResult result9 = LoopExecutionResult.fromText(text9Lines);
        LoopExecutionResult result11 = LoopExecutionResult.fromText(text11Lines);

        String fp9 = LoopScheduler.computeFingerprint(result9);
        String fp11 = LoopScheduler.computeFingerprint(result11);

        assertNotEquals(fp9, fp11,
                "texts with 9 vs 11 lines should be in different line buckets");
    }

    @Test
    void fingerprintSameForSameContent() {
        String content = "same content\nwith tool calls\nand three lines";

        LoopExecutionResult r1 = LoopExecutionResult.fromText(content);
        LoopExecutionResult r2 = LoopExecutionResult.fromText(content);

        assertEquals(LoopScheduler.computeFingerprint(r1), LoopScheduler.computeFingerprint(r2),
                "same content should produce same fingerprint");
    }
}
