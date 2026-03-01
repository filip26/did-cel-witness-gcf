
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class KmsExporter {

    public byte[] exportRawPublicKey(String projectId, String location, String keyRing, String key, String version) throws Exception {
        try (KeyManagementServiceClient client = KeyManagementServiceClient.create()) {
            CryptoKeyVersionName name = CryptoKeyVersionName.of(projectId, location, keyRing, key, version);
            
            // 1. Get Public Key from KMS (Returns X.509 PEM)
            String pem = client.getPublicKey(name).getPem();
            byte[] derEncoded = decodePem(pem);

            // 2. Try to parse as EdDSA (Ed25519) first
            try {
                KeyFactory edkf = KeyFactory.getInstance("EdDSA");
                PublicKey pubKey = edkf.generatePublic(new X509EncodedKeySpec(derEncoded));
                if (pubKey instanceof EdECPublicKey edKey) {
                    return extractEd25519Bytes(edKey);
                }
            } catch (Exception e) {
                // Not Ed25519, fall back to NIST EC (P-256/P-384)
            }

            // 3. Fallback to NIST EC
            KeyFactory eckf = KeyFactory.getInstance("EC");
            ECPublicKey ecKey = (ECPublicKey) eckf.generatePublic(new X509EncodedKeySpec(derEncoded));
            return compressNistKey(ecKey);
        }
    }

    private byte[] extractEd25519Bytes(EdECPublicKey key) {
        // Ed25519 public keys in Java are represented by an EdECPoint.
        // The "Y" coordinate already contains the 255-bit y-value 
        // and the MSB parity bit for x, following RFC 8032.
        byte[] raw = key.getPoint().getY().toByteArray();
        
        // Ensure exactly 32 bytes (BigInteger might add a leading 0x00)
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, fixed, 32 - length, length);
        
        // Ed25519 is Little-Endian; Java's BigInteger is Big-Endian.
        // Most raw Ed25519 consumers expect Little-Endian.
        reverseArray(fixed);
        return fixed;
    }

    private byte[] compressNistKey(ECPublicKey pubKey) {
        int fieldSize = (pubKey.getParams().getCurve().getField().getFieldSize() + 7) / 8;
        byte[] x = normalize(pubKey.getW().getAffineX().toByteArray(), fieldSize);
        byte prefix = pubKey.getW().getAffineY().testBit(0) ? (byte) 0x03 : (byte) 0x02;

        byte[] compressed = new byte[1 + fieldSize];
        compressed[0] = prefix;
        System.arraycopy(x, 0, compressed, 1, fieldSize);
        return compressed;
    }

    private byte[] normalize(byte[] data, int length) {
        byte[] fixed = new byte[length];
        int srcPos = Math.max(0, data.length - length);
        int destPos = Math.max(0, length - data.length);
        System.arraycopy(data, srcPos, fixed, destPos, Math.min(data.length, length));
        return fixed;
    }

    private void reverseArray(byte[] array) {
        for (int i = 0; i < array.length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - 1 - i];
            array[array.length - 1 - i] = temp;
        }
    }

    private byte[] decodePem(String pem) {
        String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                          .replace("-----END PUBLIC KEY-----", "")
                          .replaceAll("\\s", "");
        return Base64.getDecoder().decode(clean);
    }
}