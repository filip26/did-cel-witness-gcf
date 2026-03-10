import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.kms.v1.PublicKey;
import com.google.common.util.concurrent.MoreExecutors;

import jakarta.json.stream.JsonParser;

class Document {

    private final Map<String, Object> document;

    private final String assertionKmsKeyId;
    private final List<Map<String, String>> kmsKeys;
    private final List<Entry<String, Consumer<String>>> kmsKeyRefs;

    private Map.Entry<String, PublicKey> assertionKey;

    private Document(
            Map<String, Object> document,
            String assertionKmsKeyId,
            List<Map<String, String>> kmsKeys,
            List<Entry<String, Consumer<String>>> kmsKeyRefs) {
        this.document = document;
        this.assertionKmsKeyId = assertionKmsKeyId;
        this.kmsKeys = kmsKeys;
        this.kmsKeyRefs = kmsKeyRefs;

        this.assertionKey = null;
    }

    // assembly initial did document
    public static Document read(JsonParser parser) {

        if (!parser.hasNext() || parser.next() != JsonParser.Event.START_OBJECT) {
            throw new IllegalArgumentException("Root must be a JSON object");
        }

        var document = new LinkedHashMap<String, Object>();

        document.put("@context", List.of(
                "https://www.w3.org/ns/did/v1.1",
                "https://w3id.org/didcel/v1"));

        while (parser.hasNext()) {
            var next = parser.next();
            if (next == JsonParser.Event.END_OBJECT) {
                break;
            }
            String key = parser.getString();
            document.put(key, processEvent(parser, parser.next()));
        }

        if (!document.containsKey("assertionMethod")) {
            throw new IllegalArgumentException("The assertionMethod is not defined.");
        }

        if (!document.containsKey("service")) {
            throw new IllegalArgumentException("A service is not defined.");
        }

        String assertionKmsKeyId = null;

        final var kmsKeys = new ArrayList<Map<String, String>>();
        final var kmsKeyRefs = new ArrayList<Entry<String, Consumer<String>>>();

        if (!document.containsKey("heartbeatFrequency")) {
            document.put("heartbeatFrequency", "P3M");
        }

        for (final var entry : document.entrySet()) {

            switch (entry.getKey()) {
            case "assertionMethod",
                    "authentication",
                    "verificationMethod",
                    "keyAgreement",
                    "capabilityInvocation",
                    "capabilityDelegation",
                    "recovery":

                final List<Object> values;

                if (entry.getValue() instanceof List list) {
                    values = list;
                } else {
                    values = List.of(entry.getValue());
                }

                int index = 0;
                for (var value : values) {
                    if (value instanceof String keyRef && keyRef.startsWith("kms:")) {

                        if (assertionKmsKeyId == null && "assertionMethod".equals(entry.getKey())) {
                            assertionKmsKeyId = keyRef;
                        }
                        final var refIndex = index;
                        kmsKeyRefs.add(Map.entry(keyRef, ref -> values.set(refIndex, ref)));

                    } else if (value instanceof Map keyMap
                            && keyMap.get("id") instanceof String keyRef
                            && keyRef.startsWith("kms:")) {

                        if (assertionKmsKeyId == null && "assertionMethod".equals(entry.getKey())) {
                            assertionKmsKeyId = keyRef;
                        }

                        kmsKeys.add(keyMap);
                    }
                    index++;
                }

            default:
                continue;
            }
        }

        if (assertionKmsKeyId == null) {
            throw new IllegalArgumentException("Missing assertionMethod KMS key.");
        }

        return new Document(document, assertionKmsKeyId, kmsKeys, kmsKeyRefs);
    }

    public final void bindKeys(
            KeyManagementServiceClient kms,
            KeyRingName kmsKeyRing) throws InterruptedException, ExecutionException {

        final var futureMap = new LinkedHashMap<String, ApiFuture<Entry<String, Entry<String, PublicKey>>>>(
                kmsKeys.size());

        for (var kmsKey : kmsKeys) {
            var kmsKeyId = kmsKey.get("id");

            if (futureMap.containsKey(kmsKeyId)) {
                continue;
            }

            final var resourceName = kmsKeyRing.toString() + "/cryptoKeys/" + kmsKeyId.substring("kms:".length());

            futureMap.put(kmsKeyId, ApiFutures.transform(
                    kms
                            .getPublicKeyCallable()
                            .futureCall(GetPublicKeyRequest.newBuilder().setName(resourceName).build()),
                    publicKey -> Map.entry(
                            kmsKeyId,
                            Map.entry(
                                    "#" + EventLog.publicKeyMultibase(publicKey),
                                    publicKey)),
                    MoreExecutors.directExecutor()));
        }

        // Combine all individual string futures into one list future
        var keyMap = ApiFutures.allAsList(futureMap.values()).get().stream()
                .collect(Collectors.toMap(
                        Entry::getKey,
                        Entry::getValue));

        for (var kmsKey : kmsKeys) {

            var kmsKeyId = kmsKey.get("id");

            var keyEntry = keyMap.get(kmsKeyId);

            if (kmsKeyId == assertionKmsKeyId) {
                assertionKey = keyEntry;
            }

            Document.overrideWithMultikey(kmsKey, keyEntry.getKey());
        }

        for (var kmsKeyRef : kmsKeyRefs) {

            var keyEntry = keyMap.get(kmsKeyRef.getKey());

            if (keyEntry == null) {
                throw new IllegalArgumentException(
                        "An unknown relative verification method reference [" + kmsKeyRef.getKey() + "]");
            }

            kmsKeyRef.getValue().accept(keyEntry.getKey());
        }

        if (assertionKey == null) {
            throw new IllegalArgumentException("Missing assertionMethod KMS key.");
        }
    }

    public Map<String, Object> update(String did) {
        document.put("id", did);
        for (var key : kmsKeys) {
            key.put("controller", did);
        }
        return document;
    }

    public Map<String, Object> root() {
        return document;
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

    private static void overrideWithMultikey(Map<String, String> map, String publicKeyMultibase) {
        map.put("id", publicKeyMultibase);
        map.put("type", "Multikey");
        map.put("publicKeyMultibase", publicKeyMultibase);
    }

    public PublicKey publicKey() {
        return assertionKey.getValue();
    }

    public String publicKeyMultibase() {
        return assertionKey.getKey();
    }
}
