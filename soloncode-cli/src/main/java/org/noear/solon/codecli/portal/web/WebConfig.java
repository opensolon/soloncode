package org.noear.solon.codecli.portal.web;

import org.noear.snack4.Feature;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.serialization.snack4.Snack4StringSerializer;

/**
 *
 * @author noear 2026/6/8 created
 *
 */
@Configuration
public class WebConfig {
    @Bean
    public void serializer(Snack4StringSerializer serializer) {
        serializer.getSerializeConfig().addFeatures(Feature.Write_DurationUsingSimple);
    }
}