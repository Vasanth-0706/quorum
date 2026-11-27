package com.quorum.checkinservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardUidsTest {

    @Test
    void normalizesCaseAndSeparators() {
        assertThat(CardUids.normalize("04:a3-1b 2c")).isEqualTo("04A31B2C");
        assertThat(CardUids.normalize("04a31b2c5d6e80")).isEqualTo("04A31B2C5D6E80");
        assertThat(CardUids.normalize("0102030405060708090A")).isEqualTo("0102030405060708090A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "04A31B", "04A31B2C5D", "XYZ12345", "04A31B2C5D6E8", "04A31B2C;"})
    void rejectsWhatIsNotA4Or7Or10ByteUid(String raw) {
        assertThatThrownBy(() -> CardUids.normalize(raw))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_CARD_UID");
    }
}
