package cloud.cholewa.appliance.nfc;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

//AES-CMAC of RFC 4493 with the AES of the JDK (it has no CMAC of its own), pinned by AesCmacTest on the vectors of
//the RFC. A key of 16 bytes, the full 16 bytes of the MAC
final class AesCmac {

    private static final int BLOCK = 16;
    private static final byte RB = (byte) 0x87;

    private AesCmac() {
    }

    static byte[] mac(final byte[] key, final byte[] message) {
        try {
            final Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            final byte[] k1 = shifted(aes.doFinal(new byte[BLOCK]));
            final byte[] k2 = shifted(k1);

            final int blocks = Math.max(1, (message.length + BLOCK - 1) / BLOCK);
            final boolean complete = message.length > 0 && message.length % BLOCK == 0;
            final byte[] last = new byte[BLOCK];
            final int lastStart = (blocks - 1) * BLOCK;
            if (complete) {
                xor(last, message, lastStart, k1);
            } else {
                final byte[] padded = new byte[BLOCK];
                final int rest = message.length - lastStart;
                System.arraycopy(message, lastStart, padded, 0, rest);
                padded[rest] = (byte) 0x80;
                xor(last, padded, 0, k2);
            }

            byte[] x = new byte[BLOCK];
            for (int block = 0; block < blocks - 1; block++) {
                final byte[] y = new byte[BLOCK];
                xor(y, message, block * BLOCK, x);
                x = aes.doFinal(y);
            }
            final byte[] y = new byte[BLOCK];
            xor(y, last, 0, x);
            return aes.doFinal(y);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is not available", e);
        }
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

    //target = source[from .. from + 16) xor other
    private static void xor(final byte[] target, final byte[] source, final int from, final byte[] other) {
        final byte[] part = Arrays.copyOfRange(source, from, from + BLOCK);
        for (int i = 0; i < BLOCK; i++) {
            target[i] = (byte) (part[i] ^ other[i]);
        }
    }
}
