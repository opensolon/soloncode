package org.noear.solon.codecli.config.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.AgentFlags;

import java.io.Serializable;

/**
 *
 * @author noear 2026/6/2 created
 *
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class MountDo implements Serializable {
    //作用域（全局或本地）
    private String scope = AgentFlags.SCOPE_GLOBAL;

    //描述
    private String description;
    //挂载类型
    private MountType type = MountType.SKILLS;
    //配置地址支持 "~/"（用户目录相对位置） 和 "./"（工作区相对位置）
    private String path;

    //是否原始（不可删除）
    private boolean primary;
    //是否启用
    private boolean enabled = true;
    //是否可写（type == MountType.FILES 时有效）
    private boolean writeable = false;
}
