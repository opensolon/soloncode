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
package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSessionIdsTest {
    private boolean valid(String value) {
        return WebSessionIds.isValid(value);
    }

    @Test
    void acceptsWebAndDesktopSessionIds() {
        assertTrue(valid("web-demo_1"));
        assertTrue(valid("42"));
        assertFalse(valid("0"));
    }

    @Test
    void acceptsAutomationSessionIds() {
        // auto- 后缀为 UUID 十六进制（去横杠后 32 位）
        assertTrue(valid("auto-0123456789abcdef0123456789abcdef"));
        assertFalse(valid("auto-"));
        assertFalse(valid("auto-abc"));
        assertFalse(valid("auto-0123456789ABCDEF"));
        assertFalse(valid("auto/../secret"));
    }

    @Test
    void rejectsTraversalAndUnexpectedDesktopIds() {
        assertFalse(valid("../42"));
        assertFalse(valid("1/../../secret"));
        assertFalse(valid("temp-42"));
        assertFalse(valid("42.json"));
    }
}
