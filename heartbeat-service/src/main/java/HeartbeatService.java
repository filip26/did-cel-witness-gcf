import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

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
    
    // Virtual thread executor for I/O bound tasks
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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

            var futures = new ArrayList<CompletableFuture<String>>();

            while (parser.hasNext()) {

                var next = parser.next();

                if (next == JsonParser.Event.END_ARRAY) {
                    break;
                }

                var did = parser.getString();
                futures.add(addHeartbeatAsync(did));
            }

            // Wait for all updates to finish and collect results
            var results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

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

    private CompletableFuture<String> addHeartbeatAsync(String did) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final var blobId = BlobId.of(BUCKET_NAME, did);
                Blob blob = STORAGE.get(blobId);

                try (var parser = JSON_PARSER_FACTORY.createParser(new ByteArrayInputStream(blob.getContent()))) {

                    if (!parser.hasNext()) {
                        return "EMPTY";
                    }

                    var log = EventLog.parse(parser);

                    var lastEventHash = log.lastEventHash();

                    var unsignedEvent = Map.of(
                            "previousEventHash", lastEventHash,
                            "operation", Map.of("type", "heartbeat"));

//                    final var resourceName = kmsKeyRing.toString() + "/cryptoKeys/" + kmsKeyId.substring("kms:".length());

                    CryptoSuite.newSuite(null, KMS_CLIENT);
                    
                    return unsignedEvent.toString();
                }

//                STORAGE.create(BlobInfo.newBuilder(blobId).build(), updatedContent.getBytes());
//                return "OK";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }, EXECUTOR);
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
            var map = new LinkedHashMap<String, Object>();
            while (parser.hasNext()) {
                var next = parser.next();
                if (next == JsonParser.Event.END_OBJECT) {
                    break;
                }
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
                if (next == JsonParser.Event.END_ARRAY) {
                    break;
                }
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
}
