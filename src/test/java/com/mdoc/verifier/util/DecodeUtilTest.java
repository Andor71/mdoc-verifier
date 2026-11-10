package com.mdoc.verifier.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mdoc.verifier.enums.InputType;
import org.junit.jupiter.api.Test;

class DecodeUtilTest {

    private static final byte[] BYTES = {(byte) 0xfb, (byte) 0xff, 0x01};

    @Test
    void decodesEveryInputType() {
        assertThat(DecodeUtil.decodeMdocString("+/8B", InputType.BASE64)).isEqualTo(BYTES);
        assertThat(DecodeUtil.decodeMdocString("-_8B", InputType.BASE64_URL)).isEqualTo(BYTES);
        assertThat(DecodeUtil.decodeMdocString("fbff01", InputType.HEX)).isEqualTo(BYTES);
        assertThat(DecodeUtil.decodeMdocString("FBFF01", InputType.HEX)).isEqualTo(BYTES);
    }

    @Test
    void rejectsWrongAlphabet() {
        assertThatThrownBy(() -> DecodeUtil.decodeMdocString("-_8B", InputType.BASE64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecodeUtil.decodeMdocString("+/8B", InputType.BASE64_URL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecodeUtil.decodeMdocString("zz", InputType.HEX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecodeUtil.decodeMdocString("abc", InputType.HEX)).isInstanceOf(IllegalArgumentException.class);
    }
}
