package features.bot.codecli;

import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.bot.core.CliSkillProvider;
import org.noear.solon.bot.core.AgentProperties;
import org.noear.solon.core.util.Assert;
import org.noear.solon.test.SolonTest;

import java.util.Map;

/**
 *
 * @author noear 2026/2/28 created
 *
 */
@SolonTest
public class SkillTest {
    @Test
    public void case1() {
        AgentProperties config = Solon.cfg().toBean("solon.code.cli", AgentProperties.class);

        CliSkillProvider cliSkillProvider = new CliSkillProvider();

        if(Assert.isNotEmpty(config.getSkillPools())) {
            for (Map.Entry<String, String> entry : config.getSkillPools().entrySet()) {
                cliSkillProvider.skillPool(entry.getKey(), entry.getValue());
            }
        }

        //video generation animation
        //AI image video media generation

        //discoverySkill.searchSkills()

        String desc = cliSkillProvider.getPoolManager().getSkillMap().get("@shared/docusign-automation").getDescription();
        System.out.println(desc);
        assert desc.length() > 30;

        String list = cliSkillProvider.getExpertSkill().skillsearch("video generation animation");
        System.out.println(list);
        assert list.length() > 100;
    }
}
