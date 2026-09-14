package com.lightai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.runtime.ports.AccessTokenPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccessTokenPortTest {

    @Test
    void singleApplicationMappingCanSupplyDefaultModel() {
        AccessTokenPort.Principal principal = AccessTokenPort.Principal.enterprise(
                "billing", List.of("assistant"), "app-1", "key-1", null, null,
                Map.of(), Map.of("assistant", "assistant"));

        assertThat(principal.defaultAlias()).contains("assistant");
    }
}
