package org.noear.solon.codecli.workspace.git;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.AiI18n;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成提交摘要时给模型的话：与 git-summary 内置子代理同一语言规则（中文用中文，其余用英文），
 * 否则中文提示词配英文子代理（或反之）会中英混排，模型多半按中文输出。
 */
public class GitSummaryTextTest {

    static boolean hasChinese(String text) {
        return text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    @Test
    public void chineseLocalesUseChinese() {
        for (Locale locale : new Locale[]{Locale.CHINA, Locale.TRADITIONAL_CHINESE, Locale.CHINESE}) {
            GitService.SummaryText text = GitService.SummaryText.of(locale);
            String request = text.request(3, true, "\n\n=== a.txt ===\n+x");

            assertTrue(request.startsWith("请根据以下 Git diff 变更内容，生成提交信息摘要。"), request);
            assertTrue(request.contains("变更统计：3 个文件（仅前 15 个）"), request);
            assertTrue(request.endsWith("--- Diff 内容 ---\n\n\n=== a.txt ===\n+x"), request);
            assertEquals("(无差异内容)", text.noDiff());
            assertEquals("... (已截断)", text.fileTruncated());
            assertEquals("... (总差异过大，已截断)", text.totalTruncated());
        }
    }

    @Test
    public void otherLocalesUseEnglish() {
        //子代理只有中、英两版：未知语言与子代理一样落回英文
        for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.US, Locale.JAPANESE, Locale.GERMAN, Locale.KOREAN}) {
            GitService.SummaryText text = GitService.SummaryText.of(locale);
            String request = text.request(1, false, "\n\n=== a.txt ===\n+x");

            assertTrue(request.startsWith("Generate a commit message summary from the following Git diff."), request);
            assertTrue(request.contains("Change stats: 1 file\n"), request);
            assertTrue(request.endsWith("--- Diff ---\n\n\n=== a.txt ===\n+x"), request);
            assertFalse(hasChinese(request), request);
            assertFalse(hasChinese(text.noDiff() + text.fileTruncated() + text.totalTruncated()));
        }

        assertTrue(GitService.SummaryText.of(Locale.ENGLISH).request(2, true, "").contains("2 files (first 15 only)"));
    }

    @Test
    public void nullLocaleFollowsDefaultLocale() {
        Locale saved = AiI18n.getDefaultLocale();
        try {
            AiI18n.setDefaultLocale(Locale.CHINA);
            assertEquals("(无差异内容)", GitService.SummaryText.of(null).noDiff());

            AiI18n.setDefaultLocale(Locale.ENGLISH);
            assertEquals("(no diff content)", GitService.SummaryText.of(null).noDiff());
        } finally {
            AiI18n.setDefaultLocale(saved);
        }
    }
}
