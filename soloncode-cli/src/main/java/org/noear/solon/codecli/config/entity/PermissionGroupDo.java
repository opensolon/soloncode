package org.noear.solon.codecli.config.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author noear 2026/6/13 created
 *
 */
@Setter
@Getter
public class PermissionGroupDo {
    //允许工具  {"**"};
    private final List<String> tools = new ArrayList<>();
    //禁用工具
    private final List<String> disallowedTools = new ArrayList<>();
}
