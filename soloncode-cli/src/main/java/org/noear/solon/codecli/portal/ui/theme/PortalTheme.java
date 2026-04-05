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
package org.noear.solon.codecli.portal.ui.theme;

/**
 * Portal UI 主题定义。
 */
public class PortalTheme {
    private final String name;
    private final PortalColor accent;
    private final PortalColor accentStrong;
    private final PortalColor textPrimary;
    private final PortalColor textMuted;
    private final PortalColor textSoft;
    private final PortalColor success;
    private final PortalColor warning;
    private final PortalColor error;
    private final PortalColor separator;
    private final PortalColor userTitle;
    private final PortalColor assistantTitle;
    private final PortalColor thinkingTitle;
    private final PortalColor toolTitle;
    private final PortalColor blockTime;
    private final PortalColor thinkingBorder;
    private final PortalColor toolMeta;
    private final PortalColor toolValue;
    private final PortalColor toolResult;
    private final PortalColor toolPreview;
    private final PortalColor markdownHeader;
    private final PortalColor markdownBold;
    private final PortalColor markdownInlineCode;
    private final PortalColor markdownCodeText;
    private final PortalColor markdownCodeBorder;
    private final PortalColor markdownListBullet;
    private final PortalColor markdownListNumber;
    private final PortalColor markdownBlockquote;
    private final PortalColor markdownRule;
    private final PortalColor tableBorder;
    private final PortalColor tableHeader;

    public PortalTheme(String name,
                       PortalColor accent,
                       PortalColor accentStrong,
                       PortalColor textPrimary,
                       PortalColor textMuted,
                       PortalColor textSoft,
                       PortalColor success,
                       PortalColor warning,
                       PortalColor error,
                       PortalColor separator,
                       PortalColor userTitle,
                       PortalColor assistantTitle,
                       PortalColor thinkingTitle,
                       PortalColor toolTitle,
                       PortalColor blockTime,
                       PortalColor thinkingBorder,
                       PortalColor toolMeta,
                       PortalColor toolValue,
                       PortalColor toolResult,
                       PortalColor toolPreview,
                       PortalColor markdownHeader,
                       PortalColor markdownBold,
                       PortalColor markdownInlineCode,
                       PortalColor markdownCodeText,
                       PortalColor markdownCodeBorder,
                       PortalColor markdownListBullet,
                       PortalColor markdownListNumber,
                       PortalColor markdownBlockquote,
                       PortalColor markdownRule,
                       PortalColor tableBorder,
                       PortalColor tableHeader) {
        this.name = name;
        this.accent = accent;
        this.accentStrong = accentStrong;
        this.textPrimary = textPrimary;
        this.textMuted = textMuted;
        this.textSoft = textSoft;
        this.success = success;
        this.warning = warning;
        this.error = error;
        this.separator = separator;
        this.userTitle = userTitle;
        this.assistantTitle = assistantTitle;
        this.thinkingTitle = thinkingTitle;
        this.toolTitle = toolTitle;
        this.blockTime = blockTime;
        this.thinkingBorder = thinkingBorder;
        this.toolMeta = toolMeta;
        this.toolValue = toolValue;
        this.toolResult = toolResult;
        this.toolPreview = toolPreview;
        this.markdownHeader = markdownHeader;
        this.markdownBold = markdownBold;
        this.markdownInlineCode = markdownInlineCode;
        this.markdownCodeText = markdownCodeText;
        this.markdownCodeBorder = markdownCodeBorder;
        this.markdownListBullet = markdownListBullet;
        this.markdownListNumber = markdownListNumber;
        this.markdownBlockquote = markdownBlockquote;
        this.markdownRule = markdownRule;
        this.tableBorder = tableBorder;
        this.tableHeader = tableHeader;
    }

    public String name() {
        return name;
    }

    public PortalColor accent() {
        return accent;
    }

    public PortalColor accentStrong() {
        return accentStrong;
    }

    public PortalColor textPrimary() {
        return textPrimary;
    }

    public PortalColor textMuted() {
        return textMuted;
    }

    public PortalColor textSoft() {
        return textSoft;
    }

    public PortalColor success() {
        return success;
    }

    public PortalColor warning() {
        return warning;
    }

    public PortalColor error() {
        return error;
    }

    public PortalColor separator() {
        return separator;
    }

    public PortalColor userTitle() {
        return userTitle;
    }

    public PortalColor assistantTitle() {
        return assistantTitle;
    }

    public PortalColor thinkingTitle() {
        return thinkingTitle;
    }

    public PortalColor toolTitle() {
        return toolTitle;
    }

    public PortalColor blockTime() {
        return blockTime;
    }

    public PortalColor thinkingBorder() {
        return thinkingBorder;
    }

    public PortalColor toolMeta() {
        return toolMeta;
    }

    public PortalColor toolValue() {
        return toolValue;
    }

    public PortalColor toolResult() {
        return toolResult;
    }

    public PortalColor toolPreview() {
        return toolPreview;
    }

    public PortalColor markdownHeader() {
        return markdownHeader;
    }

    public PortalColor markdownBold() {
        return markdownBold;
    }

    public PortalColor markdownInlineCode() {
        return markdownInlineCode;
    }

    public PortalColor markdownCodeText() {
        return markdownCodeText;
    }

    public PortalColor markdownCodeBorder() {
        return markdownCodeBorder;
    }

    public PortalColor markdownListBullet() {
        return markdownListBullet;
    }

    public PortalColor markdownListNumber() {
        return markdownListNumber;
    }

    public PortalColor markdownBlockquote() {
        return markdownBlockquote;
    }

    public PortalColor markdownRule() {
        return markdownRule;
    }

    public PortalColor tableBorder() {
        return tableBorder;
    }

    public PortalColor tableHeader() {
        return tableHeader;
    }
}
