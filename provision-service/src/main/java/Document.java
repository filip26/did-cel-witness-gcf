import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.kms.v1.PublicKey;
import com.google.common.util.concurrent.MoreExecutors;

import jakarta.json.stream.JsonParser;

class Document {

    private final Map<String, Object> document;
    private final String signKeyLocalId;
    private final List<Map<String, String>> keysToBind;
    
    private Map<String, String> idToKeyName;
    private Map.Entry<String, PublicKey> signKey;

    private Document(
            Map<String, Object> document,
            String signKeyLocalId,
            List<Map<String, String>> keysToBind) {
        this.document = document;
        this.signKeyLocalId = signKeyLocalId;
        this.keysToBind = keysToBind;
        this.idToKeyName = null;
        this.signKey = null;
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
            throw new IllegalArgumentException("Missing assertionMethod KMS key.");
        }

        return new Document(document, signKeyLocalId, keysToBind);
    }

    public final void bindKeys(
            KeyManagementServiceClient kms,
            KeyRingName kmsKeyRing) throws InterruptedException, ExecutionException {

        final var futureMap = new LinkedHashMap<String, ApiFuture<Map.Entry<String, PublicKey>>>(keysToBind.size());

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
                    publicKey -> Map.entry(EventLog.publicKeyMultibase(publicKey), publicKey),
                    MoreExecutors.directExecutor()));
        }

        // Combine all individual string futures into one list future
        idToKeyName = ApiFutures.allAsList(futureMap.values()).get().stream()
                .collect(Collectors.toMap(e -> "#" + e.getKey(), e -> e.getValue().getName()))
                ;

        for (var kmsKey : keysToBind) {

            var localKeyId = kmsKey.get("id");

            try {

                // This .get() is safe and non-blocking because allAsList has completed
                var mapping = futureMap.get(localKeyId).get();

                if (localKeyId == signKeyLocalId) {
                    signKey = mapping;
                }

                Document.overrideWithMultikey(kmsKey, mapping.getKey());
            } catch (InterruptedException | ExecutionException e) {
                // This should technically not happen since the parent future succeeded
                throw new RuntimeException("Failed to retrieve pre-resolved future", e);
            }
        }

        if (signKey == null) {
            throw new IllegalArgumentException("Missing assertionMethod KMS key.");
        }
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
        map.clear();
        map.put("id", "#" + publicKeyMultibase);
        map.put("type", "Multikey");
        map.put("publicKeyMultibase", publicKeyMultibase);
    }

    private static String keyLocalId(Map<String, String> kmsKey) {
        return kmsKey.get("kmsKey") + "/" + kmsKey.getOrDefault("kmsKeyVersion", "1");
    }

    public PublicKey publicKey() {
        return signKey.getValue();
    }

    public String publicKeyMultibase() {
        return signKey.getKey();
    }
    
    public Map<String, String> getKeyMap() {
        return idToKeyName;
    }
}
