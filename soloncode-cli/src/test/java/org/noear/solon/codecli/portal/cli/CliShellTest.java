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
package org.noear.solon.codecli.portal.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CliShellTest {
    @Test
    public void completingHintTokenStaysActiveWhileTypingSameToken() {
        assertTrue(CliShell.isCompletingHintToken("/", 0, 1));
        assertTrue(CliShell.isCompletingHintToken("/c", 0, 2));
        assertTrue(CliShell.isCompletingHintToken("@reviewer", 0, 9));
        assertTrue(CliShell.isCompletingHintToken("hello @rev", 6, 10));
    }

    @Test
    public void completingHintTokenStopsAfterWhitespaceOrInvalidTrigger() {
        assertFalse(CliShell.isCompletingHintToken("@reviewer ", 0, 10));
        assertFalse(CliShell.isCompletingHintToken("hello@rev", 5, 9));
        assertFalse(CliShell.isCompletingHintToken("/c", 0, 0));
        assertFalse(CliShell.isCompletingHintToken("/c", 0, 3));
    }

    @Test
    public void largeHintCandidateCountTracksTerminalRows() {
        assertTrue(CliShell.isLargeHintCandidateCount(84, 24));
        assertTrue(CliShell.isLargeHintCandidateCount(23, 24));
        assertFalse(CliShell.isLargeHintCandidateCount(22, 24));
        assertFalse(CliShell.isLargeHintCandidateCount(84, 0));
    }
}
