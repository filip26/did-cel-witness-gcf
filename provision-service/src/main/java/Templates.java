
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

class Templates {

    private static final String[] JCS_PROOF_PARTS = new String[] {
            "{\"created\":\"",
            "\",\"cryptosuite\":\"",
            "\",\"nonce\":\"",
            "\",\"proofPurpose\":\"assertionMethod\",\"type\":\"DataIntegrityProof\",\"verificationMethod\":\"",
            "\"}" };

    private static final int JCS_PROOF_PARTS_LENGTH = Arrays.stream(JCS_PROOF_PARTS)
            .mapToInt(String::length)
            .sum();

    private Templates() {
        /* prevent instantiation */ }

    /**
     * Builds the canonical JSON proof (JCS) for hashing/signing.
     *
     * @param cryptosuite the cryptosuite identifier
     * @param created     ISO-8601 timestamp of the proof
     * @param method      verification method URI
     * @param nonce       cryptographically secure nonce
     * @return UTF-8 encoded JSON proof bytes
     */
    public static final byte[] jcsProof(
            String cryptosuite,
            String created,
            String method,
            String nonce) {

        return new StringBuilder(JCS_PROOF_PARTS_LENGTH
                + cryptosuite.length()
                + created.length()
                + nonce.length()
                + method.length())
                .append(JCS_PROOF_PARTS[0]).append(created)
                .append(JCS_PROOF_PARTS[1]).append(cryptosuite)
                .append(JCS_PROOF_PARTS[2]).append(nonce)
                .append(JCS_PROOF_PARTS[3]).append(method)
                .append(JCS_PROOF_PARTS[4])
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the complete JSON proof including a cryptographic signature.
     *
     * @param cryptosuite the cryptosuite identifier
     * @param created     ISO-8601 timestamp of the proof
     * @param method      verification method URI
     * @param nonce       cryptographically secure nonce
     * @param signature   cryptographic proof value
     * @return JSON proof
     */
    public static Map<String, String> jsonProof(
            String cryptosuite, 
            String created, 
            String method, 
            String nonce,
            String signature) {
        return Map.of(
                "type", "DataIntegrityProof",
                "cryptosuite", cryptosuite,
                "created", created,
                "nonce", nonce,
                "verificationMethod", method,
                "proofValue", signature);
    }
}
