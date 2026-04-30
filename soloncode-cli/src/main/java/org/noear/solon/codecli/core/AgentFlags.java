package org.noear.solon.codecli.core;

import org.noear.snack4.ONode;
import org.noear.solon.core.util.DateUtil;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 *
 * @author noear 2026/4/4 created
 *
 */
public class AgentFlags {
    private final static Logger LOG = LoggerFactory.getLogger(AgentFlags.class);

    public final static String FLAG_RUN = "run";
    public final static String FLAG_SERVE = "serve";
    public final static String FLAG_ACP = "acp";
    public final static String FLAG_WEB = "web";

    public static String getVersion() {
        return "v2026.5.1";
    }

    private static String lastVersion;

    public static String getLastVersion() {
        if (lastVersion == null) {
            try {
                String json = HttpUtils.http("https://solon.noear.org/soloncode/info.json")
                        .timeout(2)
                        .get();

                lastVersion = ONode.ofJson(json).get("cli_version").getValueAs();
            } catch (Throwable e) {
                LOG.warn("Update detection failed: {}", e.getMessage());
            }
        }

        return lastVersion;
    }


    public static boolean checkUpdate() {
        String tmp = getLastVersion();
        if (tmp != null) {
            Date lastDate = DateUtil.parseTry(tmp.substring(1));
            Date currDate = DateUtil.parseTry(getVersion().substring(1));

            if (lastDate != null && currDate != null) {
                if (lastDate.getTime() > currDate.getTime()) {
                    return true;
                }
            }
        }

        return false;
    }
}
