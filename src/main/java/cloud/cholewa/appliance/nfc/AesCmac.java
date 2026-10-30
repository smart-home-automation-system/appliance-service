package cloud.cholewa.appliance.nfc;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

//AES-CMAC of RFC 4493 with the AES of the JDK (it has no CMAC of its own), pinned by AesCmacTest on the vectors of
//the RFC. CMAC is a CBC-MAC whose last block is mixed with a subkey, so it runs on AES-CBC with a zero IV: the subkey
//comes from the encrypted zero block, the MAC is the last block of the CBC of the prepared message. A key of 16 bytes,
//the full 16 bytes of the MAC
final class AesCmac {

    private static final int BLOCK = 16;
    private static final byte RB = (byte) 0x87;

    private AesCmac() {
    }

    static byte[] mac(final byte[] key, final byte[] message) {
        try {
            final byte[] l = cbc(key, new byte[BLOCK]);
            final byte[] k1 = shifted(l);
            final byte[] k2 = shifted(k1);

            final int blocks = Math.max(1, (message.length + BLOCK - 1) / BLOCK);
            final boolean complete = message.length > 0 && message.length % BLOCK == 0;
            final int lastStart = (blocks - 1) * BLOCK;
            final byte[] prepared = new byte[blocks * BLOCK];
            System.arraycopy(message, 0, prepared, 0, message.length);
            if (!complete) {
                prepared[message.length] = (byte) 0x80;
            }
            final byte[] subkey = complete ? k1 : k2;
            for (int i = 0; i < BLOCK; i++) {
                prepared[lastStart + i] ^= subkey[i];
            }

            final byte[] chained = cbc(key, prepared);
            final byte[] mac = new byte[BLOCK];
            System.arraycopy(chained, lastStart, mac, 0, BLOCK);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is not available", e);
        }
    }

    private static byte[] cbc(final byte[] key, final byte[] blocks) throws GeneralSecurityException {
        final Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[BLOCK]));
        return aes.doFinal(blocks);
    }

    //the subkey step of RFC 4493: one bit to the left, Rb into the last byte when the top bit fell out
    private static byte[] shifted(final byte[] block) {
        final byte[] out = new byte[BLOCK];
        for (int i = 0; i < BLOCK; i++) {
            final int next = i + 1 < BLOCK ? (block[i + 1] & 0xFF) >>> 7 : 0;
            out[i] = (byte) (block[i] << 1 | next);
        }
        if ((block[0] & 0x80) != 0) {
            out[BLOCK - 1] ^= RB;
        }
        return out;
    }
}
