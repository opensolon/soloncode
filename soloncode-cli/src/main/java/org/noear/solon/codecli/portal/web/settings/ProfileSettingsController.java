package org.noear.solon.codecli.portal.web.settings;

import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.portal.web.service.ProfileService;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 设置：配置备份（导出/导入 zip）
 *
 * @author noear 2026/9/5
 */
public class ProfileSettingsController extends BaseSettingsController {
    private static final Logger LOG = LoggerFactory.getLogger(ProfileSettingsController.class);

    private static final Set<String> VALID_KEYS = new HashSet<>(Arrays.asList(
            ProfileService.KEY_SETTINGS, ProfileService.KEY_SKILLS, ProfileService.KEY_AGENTS,
            ProfileService.KEY_COMMANDS, ProfileService.KEY_MEMORY, ProfileService.KEY_SKINS));

    public ProfileSettingsController(org.noear.solon.codecli.workspace.WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    /**
     * 备份清单：可勾选条目及其来源路径/统计
     */
    @Get
    @Mapping("/web/settings/profile/manifest")
    public Result manifest() throws Exception {
        return Result.succeed(ProfileService.getInstance().buildManifest(settings()));
    }

    /**
     * 导出配置备份 zip（浏览器直接下载）
     */
    @Get
    @Mapping("/web/settings/profile/export")
    public void export(Context ctx, String keys, @Param(value = "includeSecrets", defaultValue = "false") boolean includeSecrets) throws Exception {
        Set<String> keySet = parseKeys(keys);
        if (keySet.isEmpty()) {
            ctx.status(400);
            ctx.output("no items selected");
            return;
        }
        try {
            byte[] zip = ProfileService.getInstance().exportZip(settings(), keySet, includeSecrets);
            ctx.contentType("application/zip");
            ctx.headerSet("Content-Disposition", "attachment; filename=\"soloncode-backup.zip\"");
            ctx.headerSet("Cache-Control", "no-cache");
            ctx.output(zip);
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.output(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Export profile failed: {}", e.getMessage());
            ctx.status(500);
            ctx.output("export failed: " + e.getMessage());
        }
    }

    /**
     * 解析备份 zip，返回预览清单（不落盘）
     */
    @Post
    @Mapping("/web/settings/profile/import/parse")
    public Result importParse(Context ctx) throws Exception {
        UploadedFile file = ctx.file("file");
        if (file == null) {
            return Result.failure("请上传文件");
        }
        try {
            byte[] bytes = readAll(file.getContent());
            return Result.succeed(ProfileService.getInstance().importParse(new ByteArrayInputStream(bytes)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Parse profile backup failed: {}", e.getMessage());
            return Result.failure("解析失败: " + e.getMessage());
        }
    }

    /**
     * 提交导入：合并 settings 分组 + 覆盖/新增资产文件，随后前端可调 /web/settings/reload 热生效
     */
    @Post
    @Mapping("/web/settings/profile/import/commit")
    public Result importCommit(Context ctx, String keys) throws Exception {
        UploadedFile file = ctx.file("file");
        if (file == null) {
            return Result.failure("请上传文件");
        }
        Set<String> keySet = parseKeys(keys);
        if (keySet.isEmpty()) {
            return Result.failure("未选择任何导入内容");
        }
        try {
            byte[] bytes = readAll(file.getContent());
            Map<String, Object> data = ProfileService.getInstance().importCommit(new ByteArrayInputStream(bytes), keySet);
            LOG.info("[Settings] Profile backup imported: applied={}", data.get("applied"));
            return Result.succeed(data);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Import profile backup failed: {}", e.getMessage());
            return Result.failure("导入失败: " + e.getMessage());
        }
    }

    /**
     * 读取流为字节数组（Java 8 兼容，避免依赖不确定的工具方法）；超过上限抛 IllegalArgumentException 防内存压力
     */
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (bos.size() + n > ProfileService.MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("上传文件过大（超过 64MB 上限）");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private Set<String> parseKeys(String keys) {
        Set<String> keySet = new HashSet<>();
        if (keys != null) {
            for (String k : keys.split(",")) {
                String t = k.trim();
                if (VALID_KEYS.contains(t)) {
                    keySet.add(t);
                }
            }
        }
        return keySet;
    }
}
