import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import com.apicatalog.jcs.Jcs;
import com.apicatalog.multibase.Multibase;
import com.apicatalog.multicodec.codec.KeyCodec;
import com.apicatalog.multicodec.codec.MultihashCodec;
import com.apicatalog.tree.io.TreeIOException;
import com.apicatalog.tree.io.java.JavaAdapter;
import com.google.cloud.kms.v1.PublicKey;

class EventLog {

    // returns public key encoded as multibase/multicodec
    public static String publicKeyMultibase(PublicKey publicKey) {

        return Multibase.BASE_58_BTC.encode(switch (publicKey.getAlgorithm()) {
        case EC_SIGN_P256_SHA256 -> KeyCodec.P256_PUBLIC_KEY.encode(
                PublicKeyExporter.exportRawECKey(publicKey));

        case EC_SIGN_P384_SHA384 -> KeyCodec.P384_PUBLIC_KEY.encode(
                PublicKeyExporter.exportRawECKey(publicKey));

        case EC_SIGN_ED25519 -> KeyCodec.ED25519_PUBLIC_KEY.encode(
                PublicKeyExporter.exportRawEDKey(publicKey));

        default ->
            throw new IllegalArgumentException("Unsupported key type [" + publicKey + "]");
        });
    }

    public static String methodSpecificId(Map<String, Object> document) {

        try {
            var c14n = Jcs.canonize(document, JavaAdapter.instance());

            var hash = MessageDigest.getInstance("SHA3-256").digest(c14n.getBytes(StandardCharsets.UTF_8));

            return Multibase.BASE_58_BTC.encode(
                    MultihashCodec.SHA3_256.encode(hash));

        } catch (TreeIOException e) {
            throw new IllegalArgumentException(e);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // assembly initial create operation
    public static Map<String, Object> newOperation(String type, Map<String, Object> document) {
        return Map.of(
                        "type", type,
                        "data", document);
    }

}
