package cloud.cholewa.appliance.nfc;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

//the examples of RFC 4493, section 4: an empty message, one block, a message ending inside a block, four blocks
class AesCmacTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] KEY = HEX.parseHex("2b7e151628aed2a6abf7158809cf4f3c");
    private static final String MESSAGE = "6bc1bee22e409f96e93d7e117393172a"
        + "ae2d8a571e03ac9c9eb76fac45af8e51"
        + "30c81c46a35ce411e5fbc1191a0a52ef"
        + "f69f2445df4f9b17ad2b417be66c3710";

    @Test
    void should_mac_an_empty_message() {
        assertThat(mac("")).isEqualTo("bb1d6929e95937287fa37d129b756746");
    }

    @Test
    void should_mac_one_block() {
        assertThat(mac(MESSAGE.substring(0, 32))).isEqualTo("070a16b46b4d4144f79bdd9dd04a287c");
    }

    @Test
    void should_mac_a_message_ending_inside_a_block() {
        assertThat(mac(MESSAGE.substring(0, 80))).isEqualTo("dfa66747de9ae63030ca32611497c827");
    }

    @Test
    void should_mac_four_blocks() {
        assertThat(mac(MESSAGE)).isEqualTo("51f0bebf7e3b9d92fc49741779363cfe");
    }

    private static String mac(final String message) {
        return HEX.formatHex(AesCmac.mac(KEY, HEX.parseHex(message)));
    }
}
