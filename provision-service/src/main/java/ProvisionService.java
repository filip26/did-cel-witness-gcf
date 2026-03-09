
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.apicatalog.tree.io.jakarta.JakartaGenerator;
import com.apicatalog.tree.io.java.JavaAdapter;
import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.stream.JsonGeneratorFactory;
import jakarta.json.stream.JsonParserFactory;

public class ProvisionService implements HttpFunction {

    private static final Logger LOG = Logger.getLogger(ProvisionService.class.getName());

    /**
     * Reusable KMS client to minimize latency during "warm" starts. Initialized
     * once per container instance.
     */
    private static final KeyManagementServiceClient KMS_CLIENT;

    // Static initialization
    private static final JsonParserFactory JSON_PARSER_FACTORY = Json.createParserFactory(Map.of());
    private static final JsonGeneratorFactory JSON_GENERATOR_FACTORY = Json.createGeneratorFactory(Map.of());

    // Static configuration detected at startup
    private static final KeyRingName KEY_RING;

    static {
        var kmsLocation = System.getenv("KMS_LOCATION");
        var kmsKeyRingName = System.getenv("KMS_KEY_RING");

        if (kmsLocation == null || kmsKeyRingName == null) {
            throw new IllegalStateException("Incomplete environment configuration");
        }

        var project = ServiceOptions.getDefaultProjectId();

        KEY_RING = KeyRingName.of(project, kmsLocation, kmsKeyRingName);

        try {

            KMS_CLIENT = KeyManagementServiceClient.create();

            // Ensure client is closed when the JVM shuts down
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (KMS_CLIENT != null) {
                    KMS_CLIENT.close();
                }
            }));

            // TODO check IAM rights

            LOG.info(String.format("Initialized for %s", KEY_RING.toString()));

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

        final Document document;

        try (final var parser = JSON_PARSER_FACTORY.createParser(request.getInputStream())) {

            document = Document.read(parser);

        } catch (JsonException | IllegalArgumentException e) {
            sendError(response, 400, "Bad Request", e.getMessage());
            return;

        } catch (Exception e) {
            sendError(response, 400, "Bad Request", "Malformatted body");
            return;
        }

        try {

            document.bindKeys(KMS_CLIENT, KEY_RING);

            // create new did:cel:method-specific-id
            final var methodSpecificId = EventLog.methodSpecificId(document.root());

            // create the did:cel identifier
            final var did = "did:cel:" + methodSpecificId;

            // update initial DID document
            document.update(did);

            // assembly initial create operation
            final var operation = EventLog.newOperation("create", document.root());

            // the initial create event
            final var event = new LinkedHashMap<String, Object>();
            event.put("operation", operation);

            // proof verification method
            final var verificationMethod = did + "#" + document.publicKeyMultibase();

            final var suite = CryptoSuite.newSuite(document.publicKey(), KMS_CLIENT);

            // sign the event
            final var proof = suite.sign(event, verificationMethod);

            // add proof the event
            event.put("proof", proof);

            // assembly initial log
            final var log = Map.of(
                    "keys",
                    document.getKeyMap().entrySet().stream()
                            .collect(Collectors.toMap(e -> "#" + e.getKey(), e -> e.getValue().getName())),
                    "log",
                    List.of(Map.of("event", event)));

            response.setStatusCode(200, "OK");
            response.setContentType("application/json");

            // serialize as JSON
            try (final var gen = JSON_GENERATOR_FACTORY.createGenerator(response.getOutputStream())) {
                final var writer = new JakartaGenerator(gen);
                writer.node(log, JavaAdapter.instance());
            }

        } catch (IllegalArgumentException e) {
            sendError(response, 400, "Bad Request", e.getMessage());

        } catch (Exception e) {
            LOG.severe(e.getMessage());
            sendError(response, 500, "Internal Error", e.getMessage());
        }
    }

    private static void sendError(HttpResponse response, int code, String status, String message) throws IOException {
        response.setStatusCode(code, status);
        response.setContentType("application/json");

        try (final var gen = JSON_GENERATOR_FACTORY.createGenerator(response.getWriter())) {
            gen.writeStartObject()
                    .write("status", status)
                    .write("message", message)
                    .writeEnd();
        }
    }
}
