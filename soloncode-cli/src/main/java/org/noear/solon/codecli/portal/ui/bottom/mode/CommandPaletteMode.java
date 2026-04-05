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
package org.noear.solon.codecli.portal.ui.bottom.mode;

import org.noear.solon.codecli.portal.ui.bottom.CompletionCandidate;
import org.noear.solon.codecli.portal.ui.bottom.CompletionState;
import org.noear.solon.codecli.portal.ui.bottom.panel.BottomListPanel;

import java.util.function.Supplier;

/**
 * / 命令补全面板。
 */
public class CommandPaletteMode extends CompletionMode {
    public CommandPaletteMode(CompletionState state, Supplier<String> promptSupplier) {
        super(state, promptSupplier);
    }

    @Override
    protected CompletionState.Mode mode() {
        return CompletionState.Mode.COMMAND;
    }

    @Override
    protected BottomListPanel.Item<Object> toItem(CompletionCandidate candidate) {
        return new BottomListPanel.Item<Object>(
                candidate,
                candidate.getDisplayWithSuffix(),
                candidate.getDescription(),
                BottomListPanel.Tone.TEXT);
    }
}
