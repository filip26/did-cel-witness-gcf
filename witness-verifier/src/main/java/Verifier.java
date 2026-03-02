
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.util.function.Function;

import com.apicatalog.multibase.Multibase;
import com.apicatalog.multicodec.codec.KeyCodec;

final class Verifier {

    @FunctionalInterface
    public static interface ProofCanonizer {
        byte[] apply(
                String cryptosuite,
                String created,
                String method,
                String nonce);
    }

    private final String suiteName;

    private final Function<String, byte[]> documentC14n;
    private final ProofCanonizer proofC14n;

    public Verifier(
            String name,
            Function<String, byte[]> documentC14n,
            ProofCanonizer proofC14n) {
        this.suiteName = name;
        this.documentC14n = documentC14n;
        this.proofC14n = proofC14n;
    }

    public static Verifier newVerifier(String cryptosuite) {

        return switch (cryptosuite) {
        case "ecdsa-jcs-2019", "eddsa-jcs-2022" ->
            new Verifier(
                    cryptosuite,
                    Templates::jcsDocument,
                    Templates::jcsProof);
        case "ecdsa-rdfc-2019", "eddsa-rdfc-2022" ->
            new Verifier(
                    cryptosuite,
                    Templates::rdfcDocument,
                    Templates::rdfcProof);
        default -> throw new IllegalArgumentException();
        };

//        default ->
//            throw new IllegalStateException("Unsupported KMS Key Algorithm [" + algorithm + "]");
//        };
    }

    /**
     * Computes H(canonicalDocument) || H(canonicalProof) using the specified digest
     * algorithm.
     *
     * @param algorithm         the hash algorithm (e.g. "SHA-256")
     * @param canonicalDocument the canonicalized document bytes
     * @param canonicalProof    the canonicalized proof bytes
     * @return concatenation of H(canonicalDocument) and H(canonicalProof)
     * @throws NoSuchAlgorithmException if the algorithm is unavailable
     */
    private static byte[] hash(String algorithm,
            byte[] canonicalDocument,
            byte[] canonicalProof)
            throws NoSuchAlgorithmException {

        var md = MessageDigest.getInstance(algorithm);

        md.update(canonicalProof);
        var proofHash = md.digest();

        md.update(canonicalDocument);
        var docHash = md.digest();

        var result = new byte[proofHash.length + docHash.length];
        System.arraycopy(proofHash, 0, result, 0, proofHash.length);
        System.arraycopy(docHash, 0, result, proofHash.length, docHash.length);
        return result;
    }

    public boolean verify(
            PublicKey publicKey,
            byte[] signature,
            String digest,
            String created,
            String method,
            String nonce) throws InvalidKeyException, Exception {

        publicKey = RawKeyImporter.loadCompressedP256(
                KeyCodec.P256_PUBLIC_KEY.decode(
                        Multibase.BASE_58_BTC.decode("zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY"))

        );
        signature = Multibase.BASE_58_BTC.decode(
                "z381yXZWPi84qKPBQTz8ugg23v5Qd6BQ1qMYfCmU36qH4N8KeCvMzxHWvpQ45n9rrXTSCaKCWQKaGoTXKY9eGBEuvDUVYuani");
        digest = "z5C5b1uzYJN6pDR3aWgAqUMo";
        method = "did:key:zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY#zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY";

        created = "2026-03-01T21:15:16Z";
        nonce = "1RLi73qEfeGU-O-tC_BGO-zuj2A-ndkrTiU5OK2APAQ";

        final String digestName;
        final String signatureName;

        if (publicKey instanceof ECPublicKey ecKey) {
            int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();

            if (fieldSize <= 256) {
                digestName = "SHA-256";
                signatureName = "SHA256withECDSA";

            } else if (fieldSize <= 384) {
                digestName = "SHA-384";
                signatureName = "SHA384withECDSA";

            } else {
                digestName = "SHA-512";
                signatureName = "SHA512withECDSA";
            }

        } else if (publicKey.getAlgorithm().equals("Ed25519")) {
            digestName = "SHA-256";
            signatureName = "Ed25519";

        } else {
            throw new IllegalArgumentException("Unsupported public key: " + publicKey);
        }

        var canonicalDocument = documentC14n.apply(digest);
        var canonicalProof = proofC14n.apply(suiteName, created, method, nonce);

        try {

            var hash = hash(digestName, canonicalDocument, canonicalProof);

            var verifier = Signature.getInstance(signatureName);

            verifier.initVerify(publicKey);
            verifier.update(hash);

            return verifier.verify(signature);

        } catch (NoSuchAlgorithmException | SignatureException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws InvalidKeyException, Exception {

        var cs = Verifier.newVerifier(
//                CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256,
                "ecdsa-jcs-2019");

        var x = cs.verify(
                null,
                null,
                null,
                null,
                null,
                null);

        IO.println(x);

    }
}
