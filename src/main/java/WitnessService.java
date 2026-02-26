

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

import com.apicatalog.multibase.Multibase;
import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.spi.JsonProvider;

/**
 * A Google Cloud Function that provides EdDSA (Ed25519) digital signatures
 * using Google Cloud Key Management Service (KMS). *
 * <p>
 * This implementation leverages Java 25 features and ensures high performance
 * by reusing the KMS client across function invocations. *
 * <h3>Required Environment Variables:</h3>
 * <ul>
 * <li><code>KMS_LOCATION</code> - The GCP region of the KeyRing (e.g.,
 * us-central1).</li>
 * <li><code>KMS_KEY_RING</code> - The name of the KMS KeyRing.</li>
 * <li><code>KMS_KEY_ID</code> - The name of the Asymmetric Signing Key.</li>
 * <li><code>KMS_KEY_VERSION</code> - (Optional) The version of the key.
 * Defaults to "1".</li>
 * </ul>
 */
public class WitnessService implements HttpFunction {

    private static final Logger LOG = Logger.getLogger(WitnessService.class.getName());

    /**
     * Reusable KMS client to minimize latency during "warm" starts. Initialized
     * once per container instance.
     */
    private static final KeyManagementServiceClient KMS_CLIENT;

    // Static initialization
    private static final JsonProvider JSON = JsonProvider.provider();

    // Environment variables
    private static final String VERIFICATION_METHOD;

    // Static configuration detected at startup
    private static final String RESOURCE_NAME;
    private static final CryptoSuite CRYPTOSUITE;

    static {

        var location = System.getenv("KMS_LOCATION");
        var keyRing = System.getenv("KMS_KEY_RING");
        var keyId = System.getenv("KMS_KEY_ID");

        var version = System.getenv().getOrDefault("KMS_KEY_VERSION", "1");
        var method = System.getenv("VERIFICATION_METHOD");
        var c14n = System.getenv("C14N");

        if (location == null || keyRing == null || keyId == null || method == null || c14n == null) {
            throw new IllegalStateException("Incomplete environment configuration");
        }

        // keep the input method as JSON string
        VERIFICATION_METHOD = method;

        var project = ServiceOptions.getDefaultProjectId();

        try {
            RESOURCE_NAME = CryptoKeyVersionName.format(
                    project,
                    location,
                    keyRing,
                    keyId,
                    version);

            KMS_CLIENT = KeyManagementServiceClient.create();

            // Ensure client is closed when the JVM shuts down
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (KMS_CLIENT != null) {
                    KMS_CLIENT.close();
                }
            }));

            final var keyAlgorithm = KMS_CLIENT.getCryptoKeyVersion(RESOURCE_NAME).getAlgorithm();

            CRYPTOSUITE = CryptoSuite.newSuite(
                    keyAlgorithm,
                    c14n,
                    switch (keyAlgorithm) {
                    case EC_SIGN_P256_SHA256 -> WitnessService::ec256Sign;
                    case EC_SIGN_P384_SHA384 -> WitnessService::ec384Sign;
                    case EC_SIGN_ED25519 -> WitnessService::ed256Sign;
                    default ->
                        throw new IllegalStateException("Unsupported KMS Key Algorithm [" + keyAlgorithm + "]");
                    });

            LOG.info(String.format("Initialized for %s with %s (%d bytes).",
                    CRYPTOSUITE.name(),
                    RESOURCE_NAME,
                    CRYPTOSUITE.keyLength()));

        } catch (IOException e) {
            throw new IllegalStateException("KMS initialization failed", e);
        }
    }

    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            sendError(response, 405, "Method Not Allowed", "Use POST");
            return;
        }

        JsonObject payload = null;

        try (final var parser = JSON.createReader(request.getInputStream())) {

            payload = parser.readObject();

        } catch (JsonException e) {
            sendError(response, 400, "Bad Request", e.getMessage());
            return;

        } catch (Exception e) {
            sendError(response, 400, "Bad Request", "Malformatted body");
            return;
        }

        if (payload == null || payload.size() != 1) {
            sendError(response, 400, "Bad Request", "Malformatted body");
            return;
        }

        final String digest;

        if (payload.get("digestMultibase") instanceof JsonString jsonString) {

            digest = jsonString.getString();

        } else {
            sendError(response, 400, "Bad Request", "digestMultibase value must be JSON string");
            return;
        }

        if (!Multibase.BASE_58_BTC.isEncoded(digest)
                && !Multibase.BASE_64_URL.isEncoded(digest)) {
            sendError(response, 400, "Bad Request",
                    "digestMultibase value must be multibase: base58btc or baseURLnopad ");
            return;
        }

        try {
            var proof = CRYPTOSUITE.sign(digest, VERIFICATION_METHOD);

            response.setStatusCode(200);
            response.setContentType("application/json");

            try (final var writer = response.getWriter()) {
                writer.write(proof);
            }

        } catch (Exception e) {
            LOG.severe("Signing Fault: " + e.getMessage());
            sendError(response, 500, "Signing Failed", e.getMessage());
        }
    }

    private void sendError(HttpResponse response, int code, String status, String message) throws IOException {
        response.setStatusCode(code);
        response.setContentType("application/json");

        try (final var gen = Json.createGenerator(response.getWriter())) {
            gen.writeStartObject()
                    .write("status", status)
                    .write("message", message)
                    .writeEnd();
        }
    }

    private static byte[] ed256Sign(byte[] blob) {
        final var builder = AsymmetricSignRequest.newBuilder().setName(RESOURCE_NAME);
        builder.setData(ByteString.copyFrom(blob));
        return KMS_CLIENT.asymmetricSign(builder.build()).getSignature().toByteArray();
    }

    private static byte[] ec256Sign(byte[] blob) {
        try {
            final var hash = MessageDigest.getInstance("SHA-256").digest(blob);
            final var builder = AsymmetricSignRequest.newBuilder().setName(RESOURCE_NAME);
            builder.setDigest(Digest.newBuilder().setSha256(ByteString.copyFrom(hash)).build());
            return KMS_CLIENT.asymmetricSign(builder.build()).getSignature().toByteArray();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] ec384Sign(byte[] blob) {
        try {
            final var hash = MessageDigest.getInstance("SHA-384").digest(blob);
            final var builder = AsymmetricSignRequest.newBuilder().setName(RESOURCE_NAME);
            builder.setDigest(Digest.newBuilder().setSha384(ByteString.copyFrom(hash)).build());
            return KMS_CLIENT.asymmetricSign(builder.build()).getSignature().toByteArray();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

    }
}
