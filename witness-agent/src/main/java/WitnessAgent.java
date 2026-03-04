
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
import com.apicatalog.tree.io.jakarta.JakartaGenerator;
import com.google.cloud.ServiceOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.spi.JsonProvider;

public class WitnessAgent implements HttpFunction {

    private static final Logger LOG = Logger.getLogger(WitnessAgent.class.getName());

    // Explicitly using Virtual Threads to handle parallel I/O pipelines
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final HttpClient CLIENT = HttpClient.newBuilder()
            .executor(executor)
            .build();

    // Static initialization
    private static final JsonProvider JSON = JsonProvider.provider();
    private static final Storage STORAGE = StorageOptions.getDefaultInstance().getService();

    // Environment variables
    private static final String BUCKET_NAME;

    // Static configuration detected at startup
    private static final String PROJECT;

    static {
        BUCKET_NAME = System.getenv("BUCKET_NAME");

        if (BUCKET_NAME == null) {
            throw new IllegalStateException("Incomplete environment configuration");
        }

        PROJECT = ServiceOptions.getDefaultProjectId();

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

        var did = "did:cel:zW1jvitG8gnmFE4KRt7KeWp2DrdB7sCLXjh1tJtigd5tSRt";
        List<String> witnesses = List.of(
                "https://red-witness-5qnvfghl2q-uc.a.run.app",
                "https://white-witness-5qnvfghl2q-ey.a.run.app");

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

            final JsonObject event;

            try (final var parser = JSON.createReader(new ByteArrayInputStream(blob.getContent()))) {
                var events = parser.readObject().getJsonArray("event");
                // witness the last log event - TODO configurable per request
                event = events.getJsonObject(events.size() - 1);
            }

            // extract existing proofs
            var existingProofs = event.getJsonArray("proof");

            // remove proofs
            var unsginedEvent = existingProofs != null
                    ? JSON.createObjectBuilder(event).remove("proof").build()
                    : event;

            var c14Event = Jcs.canonize(unsginedEvent, JakartaAdapter.instance());

            final var hash = Multibase.BASE_58_BTC.encode(
                    MultihashCodec.SHA3_256.encode(
                            MessageDigest.getInstance("SHA3-256").digest(
                                    c14Event.getBytes(StandardCharsets.UTF_8))));

            // Execute independent witness requests in parallel
            final var asyncRequests = witnesses.stream()
                    .map(url -> CompletableFuture.supplyAsync(
                            () -> witnessRequest(url, hash),
                            executor))
                    .toList();

            // Wait for all requests to resolve (success or failure)
            CompletableFuture.allOf(asyncRequests.toArray(CompletableFuture[]::new)).join();

            // Collect proofs/errors
            var proofs = asyncRequests.stream()
                    .map(CompletableFuture::join)
                    .toList();

//            proofs.stream();
//            if (existingProofs != null) {
//                for (var proof : existingProofs) {
//
//                }
//            }

            response.setStatusCode(200);
            response.setContentType("application/json");

            try (final var gen = JSON.createGenerator(response.getWriter())) {
                final var writer = new JakartaGenerator(gen);
                writer.beginSequence();
                for (var proof : proofs) {
                    writer.node(proof, JakartaAdapter.instance());
                }
                writer.end();
            }

        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            sendError(response, 400, "Bad Request", e.getMessage());

        } catch (Exception e) {
            LOG.severe(e.getMessage());
            sendError(response, 500, "Internal Service Error", e.getMessage());
        }
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
            e.printStackTrace();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

//        return res.statusCode() == 200 ? res.body() : null;
        return null;
    }

//    private void appendProofs() {
//     // 3. Optimized Storage Retry Loop
//        for (int i = 0; i < 5; i++) {
//            try {
//                storage.create(
//                    BlobInfo.newBuilder(blobId).build(),
//                    combine(currentBlob.getContent(), receipts.getBytes()),
//                    Storage.BlobTargetOption.generationMatch(currentBlob.getGeneration())
//                );
//                response.setStatusCode(200);
//                return;
//            } catch (StorageException e) if (e.getCode() == 412) {
//                // Conflict: Re-read metadata only to get new generation/content and try again
//                currentBlob = storage.get(blobId);
//                if (currentBlob == null) break;
//            } catch (Exception e) {
//                break;
//            }
//        }
//        response.setStatusCode(409);
//    }

//    private void witnessPipe(String url, String digestMultibase, BlobId blob) {
//        try {
//            // Witness Call (Blocks virtual thread, not platform thread)
//            String receipt = fetch(url, digestMultibase);
//            if (receipt == null) return;
//
//            // 3. Independent Retry Loop for Storage Commit
//            for (int i = 0; i < 10; i++) {
//                try {
//                    byte[] updated = combine(blob.getContent(), ("\n" + receipt).getBytes());
//                    storage.create(
//                        BlobInfo.newBuilder(blobId).build(),
//                        updated,
//                        Storage.BlobTargetOption.generationMatch(blob.getGeneration())
//                    );
//                    return; // Anchored
//                } catch (StorageException e) if (se.getCode() == 412) {
//                    blob = storage.get(blobId); // Re-read head for next attempt
//                    if (blob == null) return;
//                }
//            }
//        } catch (Exception ignored) {}
//    }

    private byte[] combine(byte[] a, byte[] b) {
        byte[] res = new byte[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
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

    private void storeLog(String id, byte[] content) {
        // Minimal write: storage.create() only requires roles/storage.objectCreator
        STORAGE.create(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, id))
                .setContentType("application/json")
                .build(), content);
    }

//    private String callWitness(String url, String hash) throws Exception {
//        var req = java.net.http.HttpRequest.newBuilder(URI.create(url))
//                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(hash))
//                .timeout(java.time.Duration.ofSeconds(5))
//                .build();
//
//        var resp = CLIENT.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
//        if (resp.statusCode() != 200)
//            throw new RuntimeException("Witness failed: " + url);
//        return resp.body();
//    }

//    private String executeWitnessCalls(String hashHex) throws Exception {
//        List<String> urls = List.of(System.getenv("WITNESS_URLS").split(","));
//        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
//            List<StructuredTaskScope.Subtask<String>> tasks = urls.stream()
//                    .map(url -> scope.fork(() -> {
//                        var req = HttpRequest.newBuilder(URI.create(url))
//                                .POST(HttpRequest.BodyPublishers.ofString(hashHex))
//                                .build();
//                        return httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
//                    })).toList();
//            scope.join().throwIfFailed();
//            return tasks.stream().map(StructuredTaskScope.Subtask::get).collect(Collectors.joining("\n"));
//        }
//    }
//
//    private void attemptAtomicCommit(Blob logBlob, String bundle) {
//        int maxRetries = 5;
//        Blob currentBase = logBlob;
//
//        for (int i = 0; i < maxRetries; i++) {
//            // We still need a source for the witness data to use GCS Compose
//            String sidecarName = currentBase.getName() + ".witness";
//            Blob sidecar = storage.create(BlobInfo.newBuilder(bucketName, sidecarName).build(), bundle.getBytes());
//
//            try {
//                Storage.ComposeRequest comp = Storage.ComposeRequest.newBuilder()
//                        .addSource(currentBase.getName())
//                        .addSource(sidecarName)
//                        .setTarget(BlobInfo.newBuilder(bucketName, currentBase.getName()).build())
//                        .setTargetOptions(Storage.BlobTargetOption.generationMatch(currentBase.getGeneration()))
//                        .build();
//
//                storage.compose(comp);
//                storage.delete(sidecar.getBlobId());
//                return; // Success
//            } catch (StorageException e) {
//                storage.delete(sidecar.getBlobId());
//                if (e.getCode() == 412) {
//                    // Only fetch the metadata to get the new generation, don't download content
//                    currentBase = storage.get(currentBase.getBlobId(),
//                            Storage.BlobGetOption.fields(Storage.BlobField.GENERATION));
//                } else {
//                    throw e; // Fail on any other error
//                }
//            }
//        }
//        throw new RuntimeException("Failed to commit after retries due to high contention");
//    }

}
