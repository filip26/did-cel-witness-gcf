package com.apicatalog.cel.witness.verifier;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
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
                    C14nTemplates::jcsDocument,
                    C14nTemplates::jcsProof);
        case "ecdsa-rdfc-2019", "eddsa-rdfc-2022" ->
            new Verifier(
                    cryptosuite,
                    C14nTemplates::rdfcDocument,
                    C14nTemplates::rdfcProof);
        default -> throw new IllegalArgumentException("Unsupported DI cryptosuite [" + cryptosuite + "]");
        };
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
            String algorithm,
            byte[] signature,
            byte[] hash) {

        try {
            var verifier = Signature.getInstance(algorithm);

            verifier.initVerify(publicKey);
            verifier.update(hash);

            return verifier.verify(signature);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);

        } catch (InvalidKeyException | SignatureException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public boolean verify(
            byte[] rawPublicKey,
            byte[] signature,
            byte[] canonicalDocument,
            byte[] canonicalProof) {

        final String digestName;
        final String algorithm;
        final PublicKey publicKey;

        if (rawPublicKey.length == 32) {
            digestName = "SHA-256";
            algorithm = "Ed25519";
            publicKey = RawKeyImporter.loadEd25519(rawPublicKey);

        } else if (rawPublicKey.length == 33) {
            digestName = "SHA-256";
            algorithm = "SHA256withECDSA";
            publicKey = RawKeyImporter.loadNistCompressed(rawPublicKey, "secp256r1", "SHA256withECDSA");

        } else if (rawPublicKey.length == 49) {
            digestName = "SHA-384";
            algorithm = "SHA384withECDSA";
            publicKey = RawKeyImporter.loadNistCompressed(rawPublicKey, "secp384r1", "SHA384withECDSA");

        } else {
            throw new IllegalArgumentException("Unsupported public key: " + rawPublicKey);
        }

        try {

            var hash = hash(digestName, canonicalDocument, canonicalProof);

            return verify(publicKey, algorithm, signature, hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(
            String publicKeyMultibase,
            String proofValue,
            String digest,
            String created,
            String method,
            String nonce) {

        var publicKey = KeyCodec.P256_PUBLIC_KEY.decode(
                Multibase.BASE_58_BTC.decode("zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY"));
        var signature = Multibase.BASE_58_BTC.decode(
                "z381yXZWPi84qKPBQTz8ugg23v5Qd6BQ1qMYfCmU36qH4N8KeCvMzxHWvpQ45n9rrXTSCaKCWQKaGoTXKY9eGBEuvDUVYuani");

        return verify(
                publicKey,
                signature,
                digest,
                created,
                method,
                nonce);
    }

    public boolean verify(
            byte[] publicKey,
            byte[] signature,
            String digest,
            String created,
            String method,
            String nonce) {

        var canonicalDocument = documentC14n.apply(digest);
        var canonicalProof = proofC14n.apply(suiteName, created, method, nonce);

        return verify(publicKey, signature, canonicalDocument, canonicalProof);
    }

    public static void main(String[] args) throws InvalidKeyException, Exception {

        var cs = Verifier.newVerifier("ecdsa-jcs-2019");

        var x = cs.verify(
                "zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY",
                "z381yXZWPi84qKPBQTz8ugg23v5Qd6BQ1qMYfCmU36qH4N8KeCvMzxHWvpQ45n9rrXTSCaKCWQKaGoTXKY9eGBEuvDUVYuani",
                "z5C5b1uzYJN6pDR3aWgAqUMo",
                "2026-03-01T21:15:16Z",
                "did:key:zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY#zDnaer5PFEcdcb2pibj8q6BtPLhUAsF85UAAaf4HzPP4hWzNY",
                "1RLi73qEfeGU-O-tC_BGO-zuj2A-ndkrTiU5OK2APAQ");

        IO.println(x);

    }
}
