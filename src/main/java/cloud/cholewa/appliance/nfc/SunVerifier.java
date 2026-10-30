package cloud.cholewa.appliance.nfc;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

//The SUN message of an NTAG 424 DNA (HAS-343) as NFC.cool Tools sets it up: the UID and the read counter encrypted
//into picc_data, with Key 0 as the meta-read and the file-read key, and the CMAC over nothing else
//(SDMMACInputOffset = SDMMACOffset), as in the SDM chapter of NXP AN12196:
//- picc_data is one AES-CBC block (zero IV) under the key; decrypted, it starts with the byte C7 (the UID and the
//  counter mirrored, a UID of 7 bytes), then the UID, then the counter in 3 bytes with the least significant first,
//  then 5 random bytes;
//- the session key is the AES-CMAC under the key of SV2, which is the bytes 3C C3 00 01 00 80 followed by the UID
//  and the counter; the MAC is the AES-CMAC under the session key of an empty message;
//- the 8 bytes sent are the bytes 1, 3, ..., 15 of the MAC (counted from 0 - "the even-numbered bytes" of NXP,
//  who count from 1).
//Pure: no I/O, no clock - the replay check is the caller's. The key is never logged nor answered
public final class SunVerifier {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final byte[] SV2_PREFIX = {0x3C, (byte) 0xC3, 0x00, 0x01, 0x00, (byte) 0x80};
    private static final byte UID_AND_COUNTER = (byte) 0xC7;
    static final int PICC_DATA_BYTES = 16;
    static final int UID_BYTES = 7;
    static final int MAC_BYTES = 8;

    private final byte[] key;

    public SunVerifier(final byte[] key) {
        if (key.length != 16) {
            throw new IllegalArgumentException("The key of the tags must be 16 bytes");
        }
        this.key = key.clone();
    }

    //the UID and the counter of the read, when picc_data decrypts into them under the key and the MAC is the one the
    //tag computes for them; empty otherwise
    public Optional<TagRead> read(final String piccData, final String cmac) {
        final byte[] encrypted = HEX.parseHex(piccData);
        final byte[] macBytes = HEX.parseHex(cmac);
        if (encrypted.length != PICC_DATA_BYTES || macBytes.length != MAC_BYTES) {
            return Optional.empty();
        }
        final byte[] plain = decrypt(encrypted);
        if (plain[0] != UID_AND_COUNTER) {
            return Optional.empty();
        }
        final byte[] uid = new byte[UID_BYTES];
        System.arraycopy(plain, 1, uid, 0, UID_BYTES);
        final int counter = (plain[8] & 0xFF) | (plain[9] & 0xFF) << 8 | (plain[10] & 0xFF) << 16;
        return MessageDigest.isEqual(mac(uid, counter), macBytes)
            ? Optional.of(new TagRead(HEX.formatHex(uid), counter))
            : Optional.empty();
    }

    private byte[] decrypt(final byte[] encrypted) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return cipher.doFinal(encrypted);
        } catch (final GeneralSecurityException e) {
            //AES/CBC/NoPadding is in every JDK and 16 bytes are one block: not reachable
            throw new IllegalStateException("AES is not available", e);
        }
    }

    byte[] mac(final byte[] uid, final int counter) {
        final byte[] sv2 = new byte[16];
        System.arraycopy(SV2_PREFIX, 0, sv2, 0, SV2_PREFIX.length);
        System.arraycopy(uid, 0, sv2, SV2_PREFIX.length, UID_BYTES);
        sv2[13] = (byte) counter;
        sv2[14] = (byte) (counter >>> 8);
        sv2[15] = (byte) (counter >>> 16);
        final byte[] full = AesCmac.mac(AesCmac.mac(key, sv2), new byte[0]);
        final byte[] truncated = new byte[MAC_BYTES];
        for (int i = 0; i < MAC_BYTES; i++) {
            truncated[i] = full[2 * i + 1];
        }
        return truncated;
    }

    //a verified read: the UID in upper-case hex (14 characters), the read counter
    public record TagRead(String uid, int counter) {
    }
}
