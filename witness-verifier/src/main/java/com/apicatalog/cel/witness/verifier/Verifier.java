package com.apicatalog.cel.witness.verifier;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.util.function.Function;

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
    private final Function<PublicKey, String[]> alogirthms;

    private final Function<String, byte[]> documentC14n;
    private final ProofCanonizer proofC14n;

    public Verifier(
            String name,
            Function<PublicKey, String[]> algorithms,
            Function<String, byte[]> documentC14n,
            ProofCanonizer proofC14n) {
        this.suiteName = name;
        this.alogirthms = algorithms;
        this.documentC14n = documentC14n;
        this.proofC14n = proofC14n;
    }

    public static Verifier newVerifier(String cryptosuite) {
        return switch (cryptosuite) {
        case "ecdsa-jcs-2019" ->
            new Verifier(
                    cryptosuite,
                    Verifier::ecAlgos,
                    C14nTemplates::jcsDocument,
                    C14nTemplates::jcsProof);

        case "eddsa-jcs-2022" ->
            new Verifier(
                    cryptosuite,
                    Verifier::edAlgos,
                    C14nTemplates::jcsDocument,
                    C14nTemplates::jcsProof);

        case "ecdsa-rdfc-2019" ->
            new Verifier(
                    cryptosuite,
                    Verifier::ecAlgos,
                    C14nTemplates::rdfcDocument,
                    C14nTemplates::rdfcProof);

        case "eddsa-rdfc-2022" ->
            new Verifier(
                    cryptosuite,
                    Verifier::edAlgos,
                    C14nTemplates::rdfcDocument,
                    C14nTemplates::rdfcProof);

        default -> throw new IllegalArgumentException("Unsupported DI cryptosuite [" + cryptosuite + "]");
        };
    }

    public boolean verify(
            PublicKey publicKey,
            byte[] signature,
            String digest,
            String created,
            String method,
            String nonce) {
        var canonicalProof = proofC14n.apply(suiteName, created, method, nonce);
        return verify(publicKey, signature, digest, canonicalProof);
    }

    public boolean verify(PublicKey publicKey, byte[] signature, String digest, byte[] canonicalProof) {
        var canonicalDocument = documentC14n.apply(digest);
        return verify(publicKey, signature, canonicalDocument, canonicalProof);
    }

    public boolean verify(
            PublicKey publicKey,
            byte[] signature,
            byte[] canonicalDocument,
            byte[] canonicalProof) {

        try {
            final var algos = alogirthms.apply(publicKey);

            final var hash = hash(algos[0], canonicalDocument, canonicalProof);

            var verifier = Signature.getInstance(algos[1]);

            verifier.initVerify(publicKey);
            verifier.update(hash);

            return verifier.verify(signature);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);

        } catch (InvalidKeyException | SignatureException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // For ECDSA (P-256, P-384, etc.)
    private static String[] ecAlgos(PublicKey key) {
        if (key instanceof ECPublicKey ecKey) {
            var bits = ecKey.getParams().getCurve().getField().getFieldSize();
            if (bits <= 256) {
                return new String[] { "SHA-256", "SHA256withECDSA" };
            }
            if (bits <= 384) {
                return new String[] { "SHA-384", "SHA384withECDSA" };
            }
            return new String[] { "SHA-512", "SHA512withECDSA" };
        }
        throw new IllegalArgumentException("Unsupported public key [" + key + "]");
    }

    // For Ed25519
    private static String[] edAlgos(PublicKey key) {
        if (key instanceof EdECPublicKey) {
            return new String[] { "SHA-256", "Ed25519" };
        }
        throw new IllegalArgumentException();
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
}
