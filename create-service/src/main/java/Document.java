import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class Document {

    private final Map<String, Object> document;
    private final Map<String, Object> assertionMethod;

    private Document(
            Map<String, Object> document,
            Map<String, Object> assertionMethod) {
        this.document = document;
        this.assertionMethod = assertionMethod;
    }

    // assembly initial create operation
    public static Document newDocument(
            String publicKeyMultibase,
            String heartbeatFrequency,
            List<String> storageEndpoints,
            String managementEndpoint) {

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
                        "serviceEndpoint", storageEndpoints),
                Map.of(
                        "type", "CelMgmtService",
                        "serviceEndpoint", List.of(managementEndpoint))));

        return new Document(document, assertionMethod);
    }

    public Map<String, Object> update(String did) {
        document.put("id", did);
        assertionMethod.put("controller", did);
        return document;
    }

    public Map<String, Object> root() {
        return document;
    }

}
