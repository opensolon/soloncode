package org.noear.solon.codecli.workspace;

/**
 * 工作区元数据
 *
 * @author noear
 */
public class WorkspaceMeta {
    private String id;
    private String name;
    private String path;
    private long lastAccessed;
    private transient boolean isDefault;

    public WorkspaceMeta() {
    }

    public WorkspaceMeta(String id, String name, String path, long lastAccessed, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.lastAccessed = lastAccessed;
        this.isDefault = isDefault;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getLastAccessed() {
        return lastAccessed;
    }

    public void setLastAccessed(long lastAccessed) {
        this.lastAccessed = lastAccessed;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
}
