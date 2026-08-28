package org.codecli.uiext;

import org.noear.solon.Solon;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

public class UiExtensionConfig implements Plugin {
    @Override
    public void start(AppContext context) throws Throwable {
        System.out.println("[ui_extension_demo] Plugin start() invoked; registering UiExtension into MAIN context.");
        // 注册到主容器，确保 WorkspaceManager.subBeansOfType(HarnessExtension) 能发现
        Solon.context().beanMake(UiExtension.class);
    }
}
