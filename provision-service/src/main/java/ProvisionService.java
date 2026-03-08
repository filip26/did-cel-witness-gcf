
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import com.apicatalog.tree.io.jakarta.JakartaGenerator;
import com.apicatalog.tree.io.java.JavaAdapter;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.kms.v1.PublicKey;
import com.google.common.util.concurrent.MoreExecutors;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.stream.JsonGeneratorFactory;
import jakarta.json.stream.JsonParser;
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
    private static final String PROJECT;
    private static final KeyRingName KEY_RING;

    static {
        var kmsLocation = System.getenv("KMS_LOCATION");
        var kmsKeyRingName = System.getenv("KMS_KEY_RING");

        if (kmsLocation == null || kmsKeyRingName == null) {
            throw new IllegalStateException("Incomplete environment configuration");
        }

        PROJECT = ServiceOptions.getDefaultProjectId();

        KEY_RING = KeyRingName.of(PROJECT, kmsLocation, kmsKeyRingName);

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
                    kmsKeyRingName,
                    kmsLocation));

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

            if (!parser.hasNext() || parser.next() != JsonParser.Event.START_OBJECT) {
                sendError(response, 400, "Bad Request", "Root must be a JSON object");
            }

            document = Document.bind((Map<String, Object>) processEvent(parser, JsonParser.Event.START_OBJECT));

        } catch (JsonException | IllegalArgumentException e) {
            sendError(response, 400, "Bad Request", e.getMessage());
            return;

        } catch (Exception e) {
            sendError(response, 400, "Bad Request", "Malformatted body");
            return;
        }

        try {

            final var signKeyMapping = setMultikeyAsync(
                    keyLocalId(document.assertionMethod()),
                    document.getKeysToBind()).get().iterator();

            final var keyResourceName = (String) signKeyMapping.next();
            final var publicKey = (PublicKey) signKeyMapping.next();
            final var publicKeyMultibase = (String) signKeyMapping.next();

//            final var keyResourceName = CryptoKeyVersionName.format(
//                    KEY_RING.getProject(),
//                    KEY_RING.getLocation(),
//                    KEY_RING.getKeyRing(),
//                    assertionMethod.get("kmsKey"),
//                    assertionMethod.getOrDefault("kmsKeyVersion", "1"));
//
//            // get public key
//            final var publicKey = KMS_CLIENT.getPublicKey(keyResourceName);
//
//            // get public key encoded as multibase
//            final var publicKeyMultibase = EventLog.publicKeyMultibase(publicKey);
//
//            // set the key representation to Multikey
//            Document.setMultikey(assertionMethod, publicKeyMultibase);

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

            // DI proof verification method
            final var verificationMethod = did + "#" + publicKeyMultibase;

            final var suite = CryptoSuite.newSuite(publicKey.getAlgorithm(), KMS_CLIENT, keyResourceName);

            // sign the event
            final var proof = suite.sign(event, verificationMethod);

            // add proof the event
            event.put("proof", proof);

            // assembly initial log
            final var log = Map.of("log", List.of(Map.of("event", event)));

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

    private static Object processEvent(JsonParser parser, JsonParser.Event event) {
        return switch (event) {
        case START_OBJECT -> {
            // Use HashMap for 10-15% better performance over LinkedHashMap
            var map = new HashMap<String, Object>();
            while (parser.hasNext()) {
                var next = parser.next();
                if (next == JsonParser.Event.END_OBJECT)
                    break;
                // In OBJECT context, next is always KEY_NAME
                String key = parser.getString();
                map.put(key, processEvent(parser, parser.next()));
            }
            yield map;
        }
        case START_ARRAY -> {
            var list = new ArrayList<>();
            while (parser.hasNext()) {
                var next = parser.next();
                if (next == JsonParser.Event.END_ARRAY)
                    break;
                list.add(processEvent(parser, next));
            }
            yield list;
        }
        case VALUE_STRING -> parser.getString();
        case VALUE_NUMBER -> parser.getBigDecimal();
        case VALUE_TRUE -> Boolean.TRUE;
        case VALUE_FALSE -> Boolean.FALSE;
        case VALUE_NULL -> null;
        default -> null;
        };
    }

    /**
     * Asynchronously retrieves, transforms, and applies KMS Public Keys to the
     * document state. * @param kmsKeys A list of maps containing KMS key
     * configuration.
     * 
     * @return An ApiFuture that completes when all keys have been retrieved and the
     *         local state updated.
     */
    private static final ApiFuture<List<Object>> setMultikeyAsync(
            String signKeyLocalId,
            List<Map<String, String>> kmsKeys) {

        final var futureMap = new LinkedHashMap<String, ApiFuture<List<Object>>>(kmsKeys.size());

        for (var kmsKey : kmsKeys) {
            var keyName = kmsKey.get("kmsKey");
            var version = kmsKey.getOrDefault("kmsKeyVersion", "1");
            var localKeyId = keyName + "/" + version;

            if (futureMap.containsKey(localKeyId)) {
                continue;
            }

            final var resourceName = CryptoKeyVersionName.format(
                    KEY_RING.getProject(),
                    KEY_RING.getLocation(),
                    KEY_RING.getKeyRing(),
                    keyName,
                    version);

            futureMap.put(localKeyId, ApiFutures.transform(
                    KMS_CLIENT
                            .getPublicKeyCallable()
                            .futureCall(GetPublicKeyRequest.newBuilder().setName(resourceName).build()),
                    publicKey -> List.of(resourceName, publicKey, EventLog.publicKeyMultibase(publicKey)),
                    MoreExecutors.directExecutor()));
        }

        // Combine all individual string futures into one list future
        var allFutures = ApiFutures.allAsList(futureMap.values());

        // Chain the final update loop to execute once all strings are ready
        return ApiFutures.transform(
                allFutures,
                ignoredList -> {

                    List<Object> signKey = null;

                    for (var kmsKey : kmsKeys) {
                        var localKeyId = keyLocalId(kmsKey);

                        try {

                            var mapping = futureMap.get(localKeyId).get();

                            if (localKeyId == signKeyLocalId) {
                                signKey = mapping;
                            }

                            // This .get() is safe and non-blocking because allAsList has completed
                            Document.setMultikey(kmsKey, (String) mapping.get(2));
                        } catch (InterruptedException | ExecutionException e) {
                            // This should technically not happen since the parent future succeeded
                            throw new RuntimeException("Failed to retrieve pre-resolved future", e);
                        }
                    }
                    return signKey;
                },
                MoreExecutors.directExecutor());
    }

    private static String keyLocalId(Map<String, String> kmsKey) {
        return kmsKey.get("kmsKey") + "/" + kmsKey.getOrDefault("kmsKeyVersion", "1");
    }
}
