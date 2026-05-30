package org.noear.solon.codecli.portal.web.market;

/**
 * 技能市场列表项实体 — 统一封装技能在列表/搜索中的展示信息。
 *
 * @author noear 2026/5/29 created
 */
public class MarketItem {
    private String slug;
    private String name;
    private String displayName;
    private String summary;
    private String description;
    private String ownerHandle;
    private String url;        // 技能市场详情页 URL
    private long installs;
    private long stars;

    public MarketItem() {
    }

    public MarketItem slug(String slug) { this.slug = slug; return this; }
    public MarketItem name(String name) { this.name = name; return this; }
    public MarketItem displayName(String displayName) { this.displayName = displayName; return this; }
    public MarketItem summary(String summary) { this.summary = summary; return this; }
    public MarketItem description(String description) { this.description = description; return this; }
    public MarketItem ownerHandle(String ownerHandle) { this.ownerHandle = ownerHandle; return this; }
    public MarketItem url(String url) { this.url = url; return this; }
    public MarketItem installs(long installs) { this.installs = installs; return this; }
    public MarketItem stars(long stars) { this.stars = stars; return this; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwnerHandle() { return ownerHandle; }
    public void setOwnerHandle(String ownerHandle) { this.ownerHandle = ownerHandle; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public long getInstalls() { return installs; }
    public void setInstalls(long installs) { this.installs = installs; }
    public long getStars() { return stars; }
    public void setStars(long stars) { this.stars = stars; }
}
