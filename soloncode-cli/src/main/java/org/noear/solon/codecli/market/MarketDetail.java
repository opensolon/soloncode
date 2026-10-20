package org.noear.solon.codecli.market;

/**
 * 技能详情实体 — 统一封装技能的完整信息。
 *
 * @author noear 2026/5/29 created
 */
public class MarketDetail {
    private String slug;
    private String displayName;
    private String summary;
    private String description;
    private String ownerHandle;
    private long installs;
    private long stars;
    private String installSlug; // 用于安装时的实际 slug（如 owner/repo/skill-name）
    private String sourceUrl;   // 源代码仓库地址（如 GitHub 链接），用于下载回退

    // 预览扩展字段（各市场按需填充，无则前端不显示）
    private String version;        // 最新版本号
    private String changelog;      // 最新版本更新日志
    private String category;       // 分类
    private Boolean verified;      // 发布方是否认证
    private String securityStatus; // 安全扫描状态描述

    public MarketDetail() {
    }

    public MarketDetail slug(String slug) { this.slug = slug; return this; }
    public MarketDetail displayName(String displayName) { this.displayName = displayName; return this; }
    public MarketDetail summary(String summary) { this.summary = summary; return this; }
    public MarketDetail description(String description) { this.description = description; return this; }
    public MarketDetail ownerHandle(String ownerHandle) { this.ownerHandle = ownerHandle; return this; }
    public MarketDetail installs(long installs) { this.installs = installs; return this; }
    public MarketDetail stars(long stars) { this.stars = stars; return this; }
    public MarketDetail installSlug(String installSlug) { this.installSlug = installSlug; return this; }
    public MarketDetail sourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; return this; }
    public MarketDetail version(String version) { this.version = version; return this; }
    public MarketDetail changelog(String changelog) { this.changelog = changelog; return this; }
    public MarketDetail category(String category) { this.category = category; return this; }
    public MarketDetail verified(Boolean verified) { this.verified = verified; return this; }
    public MarketDetail securityStatus(String securityStatus) { this.securityStatus = securityStatus; return this; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwnerHandle() { return ownerHandle; }
    public void setOwnerHandle(String ownerHandle) { this.ownerHandle = ownerHandle; }
    public long getInstalls() { return installs; }
    public void setInstalls(long installs) { this.installs = installs; }
    public long getStars() { return stars; }
    public void setStars(long stars) { this.stars = stars; }
    public String getInstallSlug() { return installSlug; }
    public void setInstallSlug(String installSlug) { this.installSlug = installSlug; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getChangelog() { return changelog; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Boolean getVerified() { return verified; }
    public void setVerified(Boolean verified) { this.verified = verified; }
    public String getSecurityStatus() { return securityStatus; }
    public void setSecurityStatus(String securityStatus) { this.securityStatus = securityStatus; }
}
