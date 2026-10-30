package cloud.cholewa.appliance.nfc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//The verifier of the tag reads with the key of the tags (HAS-343)
@Configuration
public class NfcConfig {

    @Bean
    SunVerifier sunVerifier(final NfcProperties nfcProperties) {
        return new SunVerifier(nfcProperties.keyBytes());
    }
}
