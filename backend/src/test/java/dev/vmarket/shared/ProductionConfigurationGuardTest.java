package dev.vmarket.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductionConfigurationGuardTest {
    @Test
    void productionRejectsDocumentedDevelopmentSecrets() {
        var guard = new ProductionConfigurationGuard("production", "local-bff-secret", "local-development-only");

        assertThatThrownBy(guard::rejectDevelopmentSecretsInProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BFF_SHARED_SECRET");
    }

    @Test
    void localDevelopmentCanUseDocumentedDefaults() {
        var guard = new ProductionConfigurationGuard("local", "local-bff-secret", "local-development-only");

        assertThatCode(guard::rejectDevelopmentSecretsInProduction).doesNotThrowAnyException();
    }
}
