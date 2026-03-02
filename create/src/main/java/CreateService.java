
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.spi.JsonProvider;

public class CreateService implements HttpFunction {

    private static final Logger LOG = Logger.getLogger(CreateService.class.getName());

    /**
     * Reusable KMS client to minimize latency during "warm" starts. Initialized
     * once per container instance.
     */
    private static final KeyManagementServiceClient KMS_CLIENT;

    // Static initialization
    private static final JsonProvider JSON = JsonProvider.provider();
    private static final Storage storage = StorageOptions.getDefaultInstance().getService();

    // Environment variables
    private static final String KMS_LOCATION;
    private static final String KMS_KEY_RING;
    private static final String BUCKET_NAME;

    // Static configuration detected at startup
    private static final String PROJECT;

    static {
        KMS_LOCATION = System.getenv("KMS_LOCATION");
        KMS_KEY_RING = System.getenv("KMS_KEY_RING");
        BUCKET_NAME = System.getenv("BUCKET_NAME");

        if (KMS_LOCATION == null || KMS_KEY_RING == null || BUCKET_NAME == null) {
            throw new IllegalStateException("Incomplete environment configuration");
        }

        PROJECT = ServiceOptions.getDefaultProjectId();

        try {

            KMS_CLIENT = KeyManagementServiceClient.create();

            // Ensure client is closed when the JVM shuts down
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (KMS_CLIENT != null) {
                    KMS_CLIENT.close();
                }
            }));

            // TODO check IAM rights

            LOG.info(String.format("Initialized for %s at %s.",
                    KMS_KEY_RING,
                    KMS_LOCATION));

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

        var keyAlgorithm = "EC_SIGN_P256_SHA256";
        var hms = true;

//        var rawKey = exportRawECKey(PROJECT, KMS_LOCATION, KMS_KEY_RING, "HSM_EC_P256_SIGN", "1");
//        var rawKey = exportRawEDKey(PROJECT, KMS_LOCATION, KMS_KEY_RING, "ED25519_SIGN", "1");

//        var multikey = KeyCodec.P256_PUBLIC_KEY.encode(rawKey);
        //// var multikey = KeyCodec.ED25519_PUBLIC_KEY.encode(rawKey);
//        var base = Multibase.BASE_58_BTC.encode(multikey);

        try {
            // TODO replace with create key
            var publicKey = KMS_CLIENT.getPublicKey(CryptoKeyVersionName.of(
                    PROJECT,
                    KMS_LOCATION,
                    KMS_KEY_RING,
                    "HSM_EC_P256_SIGN",
                    "1"));

            // assembly initial DID document
            var document = DidCelLog.newDocument(publicKey);

            // create new did:cel
            var did = DidCelLog.createDid(document);

            // assembly initial create operation
            var create = DidCelLog.newCreateOperation(did, document);

            var suite = CryptoSuite.newSuite(null, keyAlgorithm, null);

            // TODO
            var proof = suite.sign(create, did);

            var initialDidCelLog = Map.of(
                    "log", List.of(Map.of(
                            "event", Map.of(
                                    "operation", create,
                                    "proof", proof))));

            // TODO store log on storage

            response.setStatusCode(200);
//            response.setContentType("application/json");
            response.setContentType("text/plain");

            try (final var writer = response.getWriter()) {
                writer.write("TODO\n");
                writer.write(initialDidCelLog.toString());

            }

        } catch (Exception e) {
            LOG.severe("Signing Fault: " + e.getMessage());
            sendError(response, 500, "Signing Failed", e.getMessage());
        }
    }

    private static void sendError(HttpResponse response, int code, String status, String message) throws IOException {
        response.setStatusCode(code);
        response.setContentType("application/json");

        try (final var gen = JSON.createGenerator(response.getWriter())) {
            gen.writeStartObject()
                    .write("status", status)
                    .write("message", message)
                    .writeEnd();
        }
    }

    private void storeLog(String did, String log) {

        var blobId = BlobId.of(BUCKET_NAME, did);

        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/json")
                .build();

        // Minimal write: storage.create() only requires roles/storage.objectCreator
        storage.create(blobInfo, log.getBytes(StandardCharsets.UTF_8));
    }

}
