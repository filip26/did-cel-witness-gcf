
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import com.apicatalog.tree.io.jakarta.JakartaGenerator;
import com.apicatalog.tree.io.java.JavaAdapter;
import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.kms.v1.ProtectionLevel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.protobuf.FieldMask;
import com.google.protobuf.util.FieldMaskUtil;

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
    private static KeyRingName PARENT;

    static {
        KMS_LOCATION = System.getenv("KMS_LOCATION");
        KMS_KEY_RING = System.getenv("KMS_KEY_RING");
        BUCKET_NAME = System.getenv("BUCKET_NAME");

        if (KMS_LOCATION == null || KMS_KEY_RING == null || BUCKET_NAME == null) {
            throw new IllegalStateException("Incomplete environment configuration");
        }

        PROJECT = ServiceOptions.getDefaultProjectId();

        PARENT = KeyRingName.of(PROJECT, KMS_LOCATION, KMS_KEY_RING);

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

        var algorithm = CryptoKeyVersionAlgorithm.valueOf("EC_SIGN_P256_SHA256");
        var useHsm = false;
        var heartbeatFrequency = "P3M";

        try {

            final var protection = useHsm
                    ? ProtectionLevel.HSM
                    : ProtectionLevel.SOFTWARE;

            final var cryptoKey = CryptoKey.newBuilder()
                    .setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                    .setVersionTemplate(
                            CryptoKeyVersionTemplate.newBuilder()
                                    .setAlgorithm(algorithm)
                                    .setProtectionLevel(protection)
                                    .build())
                    .putLabels("component", "did_cel")
                    .build();

            final var keyId = "did_cel_" + UUID.randomUUID().toString().replace("-", "_");

            final var key = KMS_CLIENT.createCryptoKey(PARENT, keyId, cryptoKey);

            final var resourceName = key.getName() + "/cryptoKeyVersions/1";

            final var suite = CryptoSuite.newSuite(algorithm, KMS_CLIENT, resourceName);

            // get public key
            final var publicKey = KMS_CLIENT.getPublicKey(resourceName);

            // get public key encoded as multibase
            final var publicKeyMultibase = EventLog.publicKeyMultibase(publicKey);

            final var storageUrl = "https://storage.googleapis.com/" + BUCKET_NAME + "/";

            // assembly initial DID document
            final var document = Document.newDocument(
                    publicKeyMultibase,
                    heartbeatFrequency,
                    List.of(storageUrl));

            // create new did:cel:method-specific-id
            final var methodSpecificId = EventLog.methodSpecificId(document.root());

            updateKeyLabel(key, "did_cel", methodSpecificId);

            // create the did:cel identifier
            final var did = "did:cel:" + methodSpecificId;

            // update initial DID document
            document.update(did);

            // assembly initial create operation
            final var operation = EventLog.newOperation("create", document.root());

            // the initial create event
            final var event = new LinkedHashMap<String, Object>();
            event.put("event", operation);

            // DI proof verification method
            final var verificationMethod = did + "#" + publicKeyMultibase;

            // sign the event
            final var proof = suite.sign(event, verificationMethod);

            // add proof the event
            event.put("proof", proof);

            // assembly initial log
            final var log = Map.of("log", List.of(event));

            // serialize as JSON
            var bos = new ByteArrayOutputStream();

            try (final var gen = JSON.createGenerator(bos)) {
                final var writer = new JakartaGenerator(gen);
                writer.node(log, JavaAdapter.instance());
            }

            var content = bos.toByteArray();

            // store log
            storeLog(methodSpecificId, content);

            response.setStatusCode(201, "Created");
            response.appendHeader("Location", storageUrl + methodSpecificId);
            response.setContentType("application/json");

            try (final var writer = response.getOutputStream()) {
                writer.write(content);
            }

        } catch (IllegalArgumentException e) {
            sendError(response, 400, "Bad Request", e.getMessage());

        } catch (Exception e) {
            LOG.severe(e.getMessage());
            sendError(response, 500, "Internal Service Error", e.getMessage());
        }
    }

    private static void sendError(HttpResponse response, int code, String status, String message) throws IOException {
        response.setStatusCode(code, status);
        response.setContentType("application/json");

        try (final var gen = JSON.createGenerator(response.getWriter())) {
            gen.writeStartObject()
                    .write("status", status)
                    .write("message", message)
                    .writeEnd();
        }
    }

    private void storeLog(String did, byte[] content) {

        var blobId = BlobId.of(BUCKET_NAME, did);

        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/json")
                .build();

        // Minimal write: storage.create() only requires roles/storage.objectCreator
        storage.create(blobInfo, content);
    }

    private static void updateKeyLabel(CryptoKey key, String label, String value) {
        // Build the updated key object
        var updatedKey = key.toBuilder()
                .putLabels(label, value)
                .build();

        // Define the FieldMask (Crucial: Prevents wiping other fields)
        var updateMask = FieldMaskUtil.fromString("labels");

        // 4. Commit the update
        KMS_CLIENT.updateCryptoKey(updatedKey, updateMask);
    }
}
