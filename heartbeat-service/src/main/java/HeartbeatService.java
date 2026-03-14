import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.util.concurrent.MoreExecutors;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.stream.JsonGeneratorFactory;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParserFactory;

public class HeartbeatService implements HttpFunction {

    private static final Logger LOG = Logger.getLogger(HeartbeatService.class.getName());

    /**
     * Reusable KMS client to minimize latency during "warm" starts. Initialized
     * once per container instance.
     */
    private static final KeyManagementServiceClient KMS_CLIENT;

    // Static initialization
    private static final JsonParserFactory JSON_PARSER_FACTORY = Json.createParserFactory(Map.of());
    private static final JsonGeneratorFactory JSON_GENERATOR_FACTORY = Json.createGeneratorFactory(Map.of());
    private static final Storage STORAGE = StorageOptions.getDefaultInstance().getService();

    // Static configuration detected at startup
    private static final KeyRingName KEY_RING;

    // Environment variables
    private static final String BUCKET_NAME;

    static {
        BUCKET_NAME = System.getenv("BUCKET_NAME");

        var kmsLocation = System.getenv("KMS_LOCATION");
        var kmsKeyRingName = System.getenv("KMS_KEY_RING");

        if (BUCKET_NAME == null || kmsLocation == null || kmsKeyRingName == null) {
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

        try (final var parser = JSON_PARSER_FACTORY.createParser(request.getInputStream())) {

            if (!parser.hasNext() || parser.next() != JsonParser.Event.START_ARRAY) {
                throw new IllegalArgumentException("Root must be a JSON array");
            }

            var futures = new ArrayList<ApiFuture<String>>();

            while (parser.hasNext()) {

                var next = parser.next();

                if (next == JsonParser.Event.END_ARRAY) {
                    break;
                }

                futures.add(addHeartbeatAsync(parseRequest(parser, next)));
            }

            // Wait for all updates to finish and collect results
            List<String> results = ApiFutures.allAsList(futures).get();

            response.setStatusCode(200);
            response.setContentType("application/json");

            try (final var gen = JSON_GENERATOR_FACTORY.createGenerator(response.getWriter())) {
                gen.writeStartObject()
                        .write("status", "OK")
                        .write("processed_count", results.size())
                        .write("results", results.toString())
                        .writeEnd();
            }

        } catch (JsonException | IllegalArgumentException e) {
            sendError(response, 400, "Bad Request", e.getMessage());
            return;

        }
    }

    private ApiFuture<String> addHeartbeatAsync(final BeatRequest request) {

        final var resourceName = KEY_RING.toString()
                + "/cryptoKeys/"
                + request.key();

        // get public key async
        var publicKey = ApiFutures.transform(
                KMS_CLIENT
                        .getPublicKeyCallable()
                        .futureCall(GetPublicKeyRequest.newBuilder()
                                .setName(resourceName)
                                .build()),
                pk -> CryptoSuite.newSuite(pk.getAlgorithm(), KMS_CLIENT),
                MoreExecutors.directExecutor());

        // get event log async
        var eventLog = readEventLogAsync(request);

        ApiFuture<List<Object>> combinedFuture = ApiFutures.allAsList(
                Arrays.asList(publicKey, eventLog));

        return ApiFutures.transform(
                combinedFuture,
                results -> {
                    var suite = (CryptoSuite) results.get(0);
                    EventLog log = (EventLog) results.get(1);

                    var lastEventHash = log.lastEventHash();

                    var unsignedEvent = Map.of(
                            "previousEventHash", lastEventHash,
                            "operation", Map.of("type", "heartbeat"));

                    // sign the event
                    var proof = suite.sign(
                            resourceName,
                            unsignedEvent,
                            request.id() + request.verificationMethod());

                    var signedEvent = new LinkedHashMap<>(unsignedEvent);
                    signedEvent.put("proof", proof);

                    return signedEvent.toString();
                },
                MoreExecutors.directExecutor());
    }

    private static ApiFuture<EventLog> readEventLogAsync(BeatRequest request) {
        final SettableApiFuture<EventLog> future = SettableApiFuture.create();

        // get event log async
        CompletableFuture.supplyAsync(() -> {

            final var blobId = BlobId.of(BUCKET_NAME, request.id().substring("did:cel:".length()));
            Blob blob = STORAGE.get(blobId);

            try (var parser = JSON_PARSER_FACTORY.createParser(new ByteArrayInputStream(blob.getContent()))) {

                if (!parser.hasNext()) {
                    throw new IllegalArgumentException();
                }

                return EventLog.parse(parser);
            }
        }, MoreExecutors.directExecutor())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        future.setException(ex);
                    } else {
                        future.set(result);
                    }
                });

        return future;
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

    private static BeatRequest parseRequest(JsonParser parser, JsonParser.Event event) {

        if (event != JsonParser.Event.START_OBJECT) {
            throw new IllegalArgumentException();
        }

        String did = null;
        String kmsKey = null;
        String method = null;
        Collection<String> witnesses = null;

        while (parser.hasNext()) {
            var next = parser.next();
            if (next == JsonParser.Event.END_OBJECT) {
                break;
            }
            // In OBJECT context, next is always KEY_NAME
            String key = parser.getString();

            switch (key) {
            case "id":
                parser.next();
                did = parser.getString();
                break;

            case "key":
                parser.next();
                kmsKey = parser.getString().substring("kms:".length());
                break;

            case "verificationMethod":
                parser.next();
                method = parser.getString();
                break;

            case "witnessEndpoint":
                witnesses = parseStringList(parser, parser.next());
                break;

            default:
                throw new IllegalArgumentException();
            }
        }

        return new BeatRequest(did, kmsKey, method, witnesses);
    }

    private static List<String> parseStringList(JsonParser parser, JsonParser.Event event) {
        return switch (event) {
        case START_ARRAY -> {
            var list = new ArrayList<String>();
            while (parser.hasNext()) {
                var next = parser.next();
                if (next == JsonParser.Event.END_ARRAY) {
                    break;
                }
                list.add(parser.getString());
            }
            yield list;
        }
        default -> throw new IllegalArgumentException();
        };
    }
}

record BeatRequest(
        String id,
        String key,
        String verificationMethod,
        Collection<String> witnesses) {
}