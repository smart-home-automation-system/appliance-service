package cloud.cholewa.appliance.nfc;

import cloud.cholewa.appliance.nfc.SunVerifier.TagRead;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//The encrypted SUN message of the SDM chapter of NXP AN12196 under the key a tag leaves the factory with (16 zero
//bytes) - also the vector of nfc-developer/sdm-backend: picc_data=EF963FF7828658A599F3041510671E88&cmac=94EED9EE65337086,
//UID 04DE5F1EACC040, counter 61. NFC.cool Tools sets the tags of the house up the same way (HAS-343): a read of a
//test tag with its key left at the factory value checked out against this class before the three were programmed.
class SunVerifierTest {

    private static final SunVerifier FACTORY_KEY = new SunVerifier(new byte[16]);
    private static final String PICC_DATA = "EF963FF7828658A599F3041510671E88";
    private static final String CMAC = "94EED9EE65337086";

    @Test
    void should_read_the_example_of_an12196() {
        assertThat(FACTORY_KEY.read(PICC_DATA, CMAC)).contains(new TagRead("04DE5F1EACC040", 61));
    }

    @Test
    void should_read_the_example_in_lower_case() {
        assertThat(FACTORY_KEY.read(PICC_DATA.toLowerCase(), CMAC.toLowerCase())).contains(new TagRead("04DE5F1EACC040", 61));
    }

    @Test
    void should_refuse_a_wrong_mac() {
        assertThat(FACTORY_KEY.read(PICC_DATA, "94EED9EE65337087")).isEmpty();
    }

    //picc_data of one read with the MAC of another: the UID and the counter are inside the MAC
    @Test
    void should_refuse_the_mac_of_another_read() {
        assertThat(FACTORY_KEY.read(PICC_DATA, "4B00064004B0B3D3")).isEmpty();
    }

    //under another key picc_data decrypts into noise, whose first byte is not the one of a UID and a counter
    @Test
    void should_refuse_a_read_under_another_key() {
        final byte[] key = new byte[16];
        key[15] = 1;

        assertThat(new SunVerifier(key).read(PICC_DATA, CMAC)).isEmpty();
    }

    @Test
    void should_refuse_values_of_a_wrong_length() {
        assertThat(FACTORY_KEY.read(PICC_DATA.substring(2), CMAC)).isEmpty();
        assertThat(FACTORY_KEY.read(PICC_DATA, CMAC.substring(2))).isEmpty();
    }

    @Test
    void should_refuse_a_key_that_is_not_aes_128() {
        assertThatThrownBy(() -> new SunVerifier(new byte[24]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The key of the tags must be 16 bytes");
    }
}
