package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.VoucherPartnerKey;
import com.innbucks.loyaltyservice.testsupport.PartnerJws;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A partner's public key is the right kind for its algorithm, or it is refused at registration (V62). */
class PartnerSigningKeysTest {

    @Test
    void p256ForEs256_andRsa2048ForRs256() {
        assertThat(PartnerSigningKeys.parse(PartnerJws.pem(PartnerJws.ecKeyPair().getPublic()),
                VoucherPartnerKey.Alg.ES256)).isNotNull();
        assertThat(PartnerSigningKeys.parse(PartnerJws.pem(PartnerJws.rsaKeyPair(2048).getPublic()),
                VoucherPartnerKey.Alg.RS256)).isNotNull();
    }

    @Test
    void theWrongKindOfKeyIsRefused() throws Exception {
        KeyPairGenerator p384 = KeyPairGenerator.getInstance("EC");
        p384.initialize(new ECGenParameterSpec("secp384r1"));
        String p384Pem = PartnerJws.pem(p384.generateKeyPair().getPublic());
        assertThatThrownBy(() -> PartnerSigningKeys.parse(p384Pem, VoucherPartnerKey.Alg.ES256))
                .hasMessageContaining("P-256");

        String smallRsa = PartnerJws.pem(PartnerJws.rsaKeyPair(1024).getPublic());
        assertThatThrownBy(() -> PartnerSigningKeys.parse(smallRsa, VoucherPartnerKey.Alg.RS256))
                .hasMessageContaining("2048");

        String ecPem = PartnerJws.pem(PartnerJws.ecKeyPair().getPublic());
        assertThatThrownBy(() -> PartnerSigningKeys.parse(ecPem, VoucherPartnerKey.Alg.RS256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerSigningKeys.parse("not a key", VoucherPartnerKey.Alg.ES256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerSigningKeys.parse("", VoucherPartnerKey.Alg.ES256))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
