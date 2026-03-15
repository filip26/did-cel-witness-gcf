
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.apicatalog.jcs.Jcs;
import com.apicatalog.multibase.Multibase;
import com.apicatalog.multicodec.codec.MultihashCodec;
import com.apicatalog.tree.io.jakarta.JakartaAdapter;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.spi.JsonProvider;

public class WitnessAgent implements HttpFunction {

    private static final Logger LOG = Logger.getLogger(WitnessAgent.class.getName());

    // Explicitly using Virtual Threads to handle parallel I/O pipelines
    private final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final HttpClient CLIENT = HttpClient.newBuilder()
            .executor(EXECUTOR)
            .build();

    // Static initialization
    private static final JsonProvider JSON = JsonProvider.provider();
    private static final Storage STORAGE = StorageOptions.getDefaultInstance().getService();

    // Environment variables
    private static final String BUCKET_NAME;

    static {
        BUCKET_NAME = System.getenv("BUCKET_NAME");

        if (BUCKET_NAME == null) {
            throw new IllegalStateException("Incomplete environment configuration");
        }

        // TODO check IAM rights

//            LOG.info(String.format("Initialized for %s at %s.",
//                    kmsKeyRingName,
//                    kmsLocation));

//        } catch (IOException e) {
//            throw new IllegalStateException("KMS initialization failed", e);
//        }
    }

    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            sendError(response, 405, "Method Not Allowed", "Use POST");
            return;
        }

        final String did;
        final List<String> witnessEndpoints;
        
        try (final var parser = JSON.createReader(request.getInputStream())) {

            var payload = parser.readObject();
            did = payload.getString("did");

            witnessEndpoints = payload.getJsonArray("witnessEndpoint").stream()
                    .map(JsonString.class::cast)
                    .map(JsonString::getString).toList();

        } catch (JsonException e) {
            sendError(response, 400, "Bad Request", e.getMessage());
            return;

        } catch (Exception e) {
            sendError(response, 400, "Bad Request", "Malformatted body");
            return;
        }

        if (did == null) {
            sendError(response, 400, "Bad Request", "Required property 'did' is missing");
            return;
        }

        if (!did.startsWith("did:cel:")) {
            sendError(response, 400, "Bad Request", "Unsupported did method [" + did + "]");
            return;
        }
        
        if (witnessEndpoints.isEmpty()) {
            sendError(response, 400, "Bad Request", "No witness endpoint is defined");
            return;
        }

        final var methodSpecificId = did.substring("did:cel:".length());

        try {
            // The event log location
            final var blobId = BlobId.of(BUCKET_NAME, methodSpecificId);

            // Get the event log
            Blob blob = STORAGE.get(blobId);

            if (blob == null) {
                sendError(response, 404, "Not Found", did + " is not found");
                return;
            }

            final JsonObject jsonLog;
            final JsonArray jsonEvents;
            final JsonObject jsonEvent;

            try (final var parser = JSON.createReader(new ByteArrayInputStream(blob.getContent()))) {

                jsonLog = parser.readObject();
                jsonEvents = jsonLog.getJsonArray("log");

                // witness the last log event - TODO configurable per request
                jsonEvent = jsonEvents.getJsonObject(jsonEvents.size() - 1);
            }

            // extract existing proofs
            var existingProofs = jsonEvent.getJsonArray("proof");

            // remove proofs
            var unsignedEvent = existingProofs != null
                    ? JSON.createObjectBuilder(jsonEvent).remove("proof").build()
                    : jsonEvent;

            var c14Event = Jcs.canonize(unsignedEvent, JakartaAdapter.instance());

            final var digestMultibase = Multibase.BASE_58_BTC.encode(
                    MultihashCodec.SHA3_256.encode(
                            MessageDigest.getInstance("SHA3-256").digest(
                                    c14Event.getBytes(StandardCharsets.UTF_8))));

            // Execute independent witness requests in parallel
            final var witnessRequests = witnessEndpoints.stream()
                    .map(url -> CompletableFuture.supplyAsync(
                            () -> witnessRequest(url, digestMultibase),
                            EXECUTOR))
                    .toList();

            // Wait for all requests to resolve (success or failure)
            CompletableFuture.allOf(witnessRequests.toArray(CompletableFuture[]::new)).join();

            // Collect proofs/errors
            var witnessProofs = witnessRequests.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // assembly witnessed event
            var witnessedBuilder = JSON.createObjectBuilder(unsignedEvent);
            var proofs = mergeProofs(existingProofs, witnessProofs);

            var witnessed = witnessedBuilder.add("proof", proofs).build();

            var updatedLog = JSON.createObjectBuilder(jsonLog);

            updatedLog.add("log", JSON.createArrayBuilder(jsonEvents)
                    .remove(jsonEvents.size() - 1)
                    .add(witnessed));

            storeLog(methodSpecificId, blob, updatedLog.build().toString().getBytes(StandardCharsets.UTF_8));

            // send response
            response.setStatusCode(200);
            response.setContentType("application/json");

            try (final var writer = response.getWriter()) {
                writer.write(witnessed.toString());
            }

        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            sendError(response, 400, "Bad Request", e.getMessage());

        } catch (Exception e) {
            e.printStackTrace();
            LOG.severe(e.getMessage());
            sendError(response, 500, "Internal Service Error", e.getMessage());
        }
    }

    private JsonArray mergeProofs(JsonArray existingProofs, List<JsonObject> witnessProofs) {
        
        var proofs = JSON.createArrayBuilder();

        if (existingProofs != null) {
            for (var proof : existingProofs) {
                proofs.add(proof);
            }
        }

        for (var proof : witnessProofs) {
            proofs.add(proof);
        }
        
        return proofs.build();
    }

    private JsonObject witnessRequest(String url, String digestMultibase) {

        var req = java.net.http.HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"digestMultibase\": \"" + digestMultibase + "\"}"))
                .build();

        try {
            var res = CLIENT.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());

            if (res.statusCode() == 200) {
                try (var reader = JSON.createReader(res.body())) {
                    return reader.readObject();
                }
            }

        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // TODO
        return null;
    }

    private void storeLog(String id, Blob blob, byte[] log) {
        // Minimal write: storage.create() only requires roles/storage.objectCreator
        STORAGE.create(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, id))
                .setContentType("application/json")
                .build(), log, Storage.BlobTargetOption.generationMatch(blob.getGeneration()));
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
}
