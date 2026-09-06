package com.lightai.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Token 签发语义（BE-044）：前缀、摘要 32 字节、掩码不可反推。 */
class AccessTokenServiceTest {

    private final AccessTokenService service =
            new AccessTokenService(AccessTokenService.fixedPepper(1, "unit-test-pepper"));

    @Test
    void issuedTokenHasLaiPrefixAndStableDigest() {
        AccessTokenService.Issued issued = service.issue();
        assertThat(issued.tokenValue()).startsWith("lai_");
        assertThat(issued.tokenHash()).hasSize(32);
        assertThat(issued.maskedValue()).startsWith("lai_****").doesNotContain(issued.tokenValue());
        assertThat(issued.pepperVersion()).isEqualTo(1);
        byte[] again = service.digest(issued.tokenValue());
        assertThat(again).isEqualTo(issued.tokenHash());
    }

    @Test
    void differentTokensProduceDifferentDigests() {
        AccessTokenService.Issued first = service.issue();
        AccessTokenService.Issued second = service.issue();
        assertThat(first.tokenValue()).isNotEqualTo(second.tokenValue());
        assertThat(first.tokenHash()).isNotEqualTo(second.tokenHash());
    }
}
