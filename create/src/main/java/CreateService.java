
import java.io.IOException;
import java.util.logging.Logger;

import com.apicatalog.multibase.Multibase;
import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;

import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
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

    // Environment variables
    private static final String PROJECT;

    // Static configuration detected at startup
    private static final String KMS_LOCATION;
    private static final String KMS_KEY_RING;
    private static final String KMS_KEY_TYPE;

    static {
        KMS_LOCATION = System.getenv("KMS_LOCATION");
        KMS_KEY_RING = System.getenv("KMS_KEY_RING");
        KMS_KEY_TYPE = System.getenv("KMS_KEY_TYPE");

        if (KMS_LOCATION == null || KMS_KEY_RING == null || KMS_KEY_TYPE == null) {
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

            //TODO check IAM rights
            
            LOG.info(String.format("Initialized for %s with %s/%s.",
                    KMS_KEY_TYPE,
                    KMS_LOCATION,
                    KMS_KEY_RING));

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
                    "digestMultibase value must be multibase: base58btc or base64URLnopad");
            return;
        }

        try {
            response.setStatusCode(200);
            response.setContentType("application/json");

            try (final var writer = response.getWriter()) {
                writer.write("TODO");
            }

        } catch (Exception e) {
            LOG.severe("Signing Fault: " + e.getMessage());
            sendError(response, 500, "Signing Failed", e.getMessage());
        }
    }

    private void sendError(HttpResponse response, int code, String status, String message) throws IOException {
        response.setStatusCode(code);
        response.setContentType("application/json");

        try (final var gen = JSON.createGenerator(response.getWriter())) {
            gen.writeStartObject()
                    .write("status", status)
                    .write("message", message)
                    .writeEnd();
        }
    }
}
