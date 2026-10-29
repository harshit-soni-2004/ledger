package dev.ihars.ledger.shared.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Text fields accept only JSON strings. Money values are strings (docs/04-api-contract.md);
 * without this, Jackson would silently turn {@code 10.10} into {@code "10.1"} and accept
 * numbers whose binary floating-point form already lost precision.
 */
@Configuration(proxyBeanMethods = false)
public class StrictJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer rejectScalarsAsText() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
