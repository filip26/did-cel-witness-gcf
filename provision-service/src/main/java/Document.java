import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class Document {

    private final Map<String, Object> document;
    private final Map<String, String> signKey;
    private final List<Map<String, String>> publicKeys;

    private Document(
            Map<String, Object> document,
            Map<String, String> signKeyId,
            List<Map<String, String>> keysToBind) {
        this.document = document;
        this.signKey = signKeyId;
        this.publicKeys = keysToBind;
    }

    // assembly initial did document
    public static Document bind(Map<String, Object> template) {

        var document = new LinkedHashMap<String, Object>(template);
        document.put("@context", List.of(
                "https://www.w3.org/ns/did/v1.1",
                "https://w3id.org/didcel/v1"));

        Map<String, String> signKeyId = null;
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
                    if (value instanceof Map keyMap && keyMap.get("kmsKey") instanceof String kmsKey) {

                        var kmsKeyVersion = keyMap.getOrDefault("kmsKeyVersion", "1");

                        var kmsKeyId = kmsKey + "/" + kmsKeyVersion;

                        if ("assertionMethod".equals(entry.getKey())) {
                            signKeyId = keyMap;

                        } else {
                            keysToBind.add(keyMap);
                        }
                    }
                }

            default:
                continue;
            }
        }

        if (signKeyId == null) {
            throw new IllegalArgumentException();
        }

        // TODO
        return new Document(document, signKeyId, keysToBind);
    }

    // assembly initial create operation
    @Deprecated
    public static Document newDocument(
            String publicKeyMultibase,
            String heartbeatFrequency,
            List<String> storageEndpoints) {

        var assertionMethod = new LinkedHashMap<String, Object>(4);

        assertionMethod.put("id", "#" + publicKeyMultibase);
        assertionMethod.put("type", "Multikey");
        assertionMethod.put("publicKeyMultibase", publicKeyMultibase);

        var document = new LinkedHashMap<String, Object>(5);
        document.put("@context", List.of(
                "https://www.w3.org/ns/did/v1.1",
                "https://w3id.org/didcel/v1"));
        document.put("heartbeatFrequency", heartbeatFrequency);
        document.put("assertionMethod", List.of(assertionMethod));
        document.put("service", List.of(
                Map.of(
                        "type", "CelStorageService",
                        "serviceEndpoint", storageEndpoints)));

//        return new Document(document, assertionMethod);
        return null;
    }

    public Map<String, Object> update(String did) {
        document.put("id", did);
        signKey.put("controller", did);
//        assertionMethod.put("controller", did);
        return document;
    }

    public Map<String, Object> root() {
        return document;
    }

    public Map<String, String> assertionMethod() {
        return signKey;
    }

    public List<Map<String, String>> getKeysToBind() {
        return publicKeys;
    }

    public static void setMultikey(Map<String, String> holder, String publicKeyMultibase) {
        holder.clear();
        holder.put("id", "#" + publicKeyMultibase);
        holder.put("type", "Multikey");
        holder.put("publicKeyMultibase", publicKeyMultibase);
    }
}
