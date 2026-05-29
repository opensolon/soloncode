package org.noear.solon.codecli.portal.web.market;

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
}
