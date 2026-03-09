import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.kms.v1.PublicKey;
import com.google.common.util.concurrent.MoreExecutors;

import jakarta.json.stream.JsonParser;

final class Document {

    private final Map<String, Object> document;
    private final String signKeyLocalId;
    private final List<Map<String, String>> keysToBind;

    private PublicKey publicKey;
    private String publicKeyMultibase;

    private Document(
            Map<String, Object> document,
            String signKeyLocalId,
            List<Map<String, String>> keysToBind) {
        this.document = document;
        this.signKeyLocalId = signKeyLocalId;
        this.keysToBind = keysToBind;
        this.publicKey = null;
        this.publicKeyMultibase = null;
    }

    // assembly initial did document
    public static Document read(JsonParser parser) {

        if (!parser.hasNext() || parser.next() != JsonParser.Event.START_OBJECT) {
            throw new IllegalArgumentException("Root must be a JSON object");
        }

        var document = (Map<String, Object>) processEvent(parser, JsonParser.Event.START_OBJECT);

        document.put("@context", List.of(
                "https://www.w3.org/ns/did/v1.1",
                "https://w3id.org/didcel/v1"));

        String signKeyLocalId = null;
        List<Map<String, String>> keysToBind = new ArrayList<>();

        if (!document.containsKey("heartbeatFrequency")) {
            document.put("heartbeatFrequency", "P3M");
        }

        for (var entry : document.entrySet()) {
            switch (entry.getKey()) {
            case "assertionMethod",
                    "authenticationMethod",
                    "recovery":

                final List<Object> values;

                if (entry.getValue() instanceof List list) {
                    values = list;
                } else {
                    values = List.of(entry.getValue());
                }

                for (var value : values) {
                    if (value instanceof Map keyMap && keyMap.containsKey("kmsKey")) {

                        var localId = keyLocalId(keyMap);

                        if ("assertionMethod".equals(entry.getKey())) {
                            signKeyLocalId = localId;
                        }

                        keyMap.put("id", localId);

                        keysToBind.add(keyMap);
                    }
                }

            default:
                continue;
            }
        }

        if (signKeyLocalId == null) {
            throw new IllegalArgumentException();
        }

        return new Document(document, signKeyLocalId, keysToBind);
    }

    public final void bindKeys(
            KeyManagementServiceClient kms,
            KeyRingName kmsKeyRing) throws InterruptedException, ExecutionException {

        final var futureMap = new LinkedHashMap<String, ApiFuture<List<Object>>>(keysToBind.size());

        for (var kmsKey : keysToBind) {
            var keyName = kmsKey.get("kmsKey");
            var version = kmsKey.getOrDefault("kmsKeyVersion", "1");
            var localKeyId = kmsKey.get("id");

            if (futureMap.containsKey(localKeyId)) {
                continue;
            }

            final var resourceName = CryptoKeyVersionName.format(
                    kmsKeyRing.getProject(),
                    kmsKeyRing.getLocation(),
                    kmsKeyRing.getKeyRing(),
                    keyName,
                    version);

            futureMap.put(localKeyId, ApiFutures.transform(
                    kms
                            .getPublicKeyCallable()
                            .futureCall(GetPublicKeyRequest.newBuilder().setName(resourceName).build()),
                    publicKey -> List.of(publicKey, EventLog.publicKeyMultibase(publicKey)),
                    MoreExecutors.directExecutor()));
        }

        // Combine all individual string futures into one list future
        ApiFutures.allAsList(futureMap.values()).get();

        List<Object> signKey = null;

        for (var kmsKey : keysToBind) {

            var localKeyId = kmsKey.get("id");

            try {

                // This .get() is safe and non-blocking because allAsList has completed
                var mapping = futureMap.get(localKeyId).get();

                if (localKeyId == signKeyLocalId) {
                    signKey = mapping;
                }

                Document.overrideWithMultikey(kmsKey, (String) mapping.get(2));
            } catch (InterruptedException | ExecutionException e) {
                // This should technically not happen since the parent future succeeded
                throw new RuntimeException("Failed to retrieve pre-resolved future", e);
            }
        }

        if (signKey == null) {
            throw new IllegalArgumentException("Missing assertionMethod KMS key.");
        }

        final var it = signKey.iterator();

        this.publicKey = (PublicKey) it.next();
        this.publicKeyMultibase = (String) it.next();
    }

    public Map<String, Object> update(String did) {
        document.put("id", did);
        for (var key : keysToBind) {
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

    private static void overrideWithMultikey(Map<String, String> map, String publicKeyMultibase) {
        map.clear();
        map.put("id", "#" + publicKeyMultibase);
        map.put("type", "Multikey");
        map.put("publicKeyMultibase", publicKeyMultibase);
    }

    private static String keyLocalId(Map<String, String> kmsKey) {
        return kmsKey.get("kmsKey") + "/" + kmsKey.getOrDefault("kmsKeyVersion", "1");
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public String publicKeyMultibase() {
        return publicKeyMultibase;
    }

}
