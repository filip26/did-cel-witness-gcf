
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Provides pre-built templates for canonical JSON and RDF Dataset
 * Canonicalization (RDFC) proofs and documents, optimized for cryptographic
 * hashing and signing.
 */
public class Templates {

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
     * Builds the canonical JSON document (JCS) with the given digest.
     *
     * @param digest multibase digest string
     * @return UTF-8 encoded JSON document bytes
     */
    public static final byte[] jcsDocument(String digest) {
        return new StringBuilder(22 + digest.length())
                .append("{\"digestMultibase\":\"")
                .append(digest)
                .append("\"}")
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the deterministic N-Quads representation of a DataIntegrityProof blank
     * node for RDF Dataset Canonicalization (RDFC).
     *
     * <p>
     * The returned value is UTF-8 encoded and suitable for hashing or signing. The
     * output strictly follows N-Quads syntax and is deterministic for the supplied
     * values.
     * </p>
     *
     * @param cryptosuite the cryptosuite identifier (literal)
     * @param created     ISO-8601 timestamp (xsd:dateTime literal)
     * @param method      verification method IRI
     * @param nonce       cryptographically secure nonce (literal)
     * @return UTF-8 encoded canonical N-Quads proof representation
     */
    public static final byte[] rdfcProof(
            String cryptosuite,
            String created,
            String method,
            String nonce) {

        return new StringBuilder(420
                + cryptosuite.length()
                + created.length()
                + method.length()
                + nonce.length())
                .append("_:c14n0 <http://purl.org/dc/terms/created> \"")
                .append(created)
                .append("\"^^<http://www.w3.org/2001/XMLSchema#dateTime> .\n_:c14n0 <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://w3id.org/security#DataIntegrityProof> .\n_:c14n0 <https://w3id.org/security#cryptosuite> \"")
                .append(cryptosuite)
                .append("\"^^<https://w3id.org/security#cryptosuiteString> .\n_:c14n0 <https://w3id.org/security#nonce> \"")
                .append(nonce)
                .append("\" .\n_:c14n0 <https://w3id.org/security#proofPurpose> <https://w3id.org/security#assertionMethod> .\n_:c14n0 <https://w3id.org/security#verificationMethod> <")
                .append(method)
                .append("> .\n")
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the canonical N-Quads representation of a digest for RDF Dataset
     * Canonicalization (RDFC).
     *
     * <p>
     * The returned value is UTF-8 encoded and suitable for hashing or signing. The
     * output strictly follows N-Quads syntax, with a blank node subject and a typed
     * literal for the digest.
     * </p>
     *
     * @param digest the multibase digest string
     * @return UTF-8 encoded canonical N-Quads representation of the digest
     */
    public static final byte[] rdfcDocument(String digest) {
        return new StringBuilder(18 + digest.length())
                .append("_:c14n0 <https://w3id.org/security#digestMultibase> \"")
                .append(digest)
                .append("\"^^<https://w3id.org/security#multibase> .\n")
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
     * @return JSON proof string
     */
    public static String jsonProof(String cryptosuite, String created, String method, String nonce, String signature) {
        return new StringBuilder(JCS_PROOF_PARTS_LENGTH + 16
                + cryptosuite.length()
                + created.length()
                + nonce.length()
                + method.length()
                + signature.length())
                .append(JCS_PROOF_PARTS[0]).append(created)
                .append(JCS_PROOF_PARTS[1]).append(cryptosuite)
                .append(JCS_PROOF_PARTS[2]).append(nonce)
                .append(JCS_PROOF_PARTS[3]).append(method)
                .append("\",\"proofValue\":\"").append(signature)
                .append(JCS_PROOF_PARTS[4])
                .toString();
    }
}
