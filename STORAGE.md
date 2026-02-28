
## Resolution (Read)

The resolution of a `did:cel` identifier is the process of retrieving and validating its event log to produce a compliant DID Document. This method is self-certifying and registry-agnostic, relying on the cryptographic integrity of the log's inception.

### Hybrid Discovery
The `did:cel` method supports a hybrid discovery model. While the `storage` parameter provides a high-performance, deterministic path for resolution, it does not represent a centralized point of failure, the identity is content-addressable, the same log can be hosted across multiple providers, peer-to-peer networks. 

### Algorithm
To resolve a `did:cel` identifier, a resolver MUST perform the following steps:

1. Extract the Commitment: Parse the `method-specific-id` from the DID string to obtain `initial-event-log-hash`.
2. Locate the Log: Retrieve the Event Log array from a distributed registry or a location specified by the `storage` parameter. If a `storage` URL is provided, the resolver MAY fetch the resource at `[URL][method-specific-id]`.
3. Verify Inception: Perform a JCS (JSON Canonicalization Scheme) serialization of the first entry in the log, `sha3-256(JCS($E_0$))`. The `sha3-256` hash of this value MUST exactly match the `initial-event-log-hash` extracted from the DID.
4. Validate Chain Integrity: Iterate through subsequent events ($E_1 \dots E_n$). For each event, verify that:
    - 
    - The `previousEventHash` matches the `sha3-256` hash of the previous event's JCS representation.
    - The event is signed by a key authorized in the state established by the previous event.
    - Witness Verification: The resolver MUST verify that the event contains a sufficient number of valid witness signatures. The specific threshold and selection of required witnesses are determined by application-level logic based on the trust requirements of the relying party.
5. Verify Liveness (Heartbeat Chain): The resolver MUST verify a contiguous chain of heartbeat proofs throughout the log duration. 
    - Frequency Match: Heartbeats MUST occur at the interval frequency defined in the DID configuration or method defaults.
    - Continuity: Any gap in the heartbeat chain that exceeds the allowed threshold—without an accompanying deactivation or authorized suspension event—MUST result in a validation failure. This ensures that a storage provider cannot omit intermediate events or "freeze" the state in the past.
6. Project State: Apply the cumulative state changes (key additions, rotations, or service updates) defined in the verified log to construct the final DID Document.
7. Validate Origin: 
  - The resulting DID Document contains an id field exactly matching the `initial-event-log-hash`.
  - If the event log was retrieved by using a provided `storage` URL parameter, then that exact URL MUST be listed as an approved `CelStorageService` within the service section of the assembled DID Document.

### Immutability and Caching

The `did:cel` event log is a cryptographically immutable ledger. Because each event $E_n$ is linked via the hash of its predecessor $E_{n-1}$, the log functions as a tamper-evident chain. 

Resolvers SHOULD cache verified events, event logs, locally. Once an event is validated against the inception commitment and the chain of signatures, it never needs to be re-verified or re-fetched.

### `storage`, `CelStorageService`, and URL Construction

The `did:cel` resolver uses a simple string concatenation rule to find logs. The final fetch URL is formed by appending the `method-specific-id` directly to the storage URL.

**Examples**

Path-Based (Static Hosting):
  * `storage`: `https://storage.googleapis.com/did-cel-log/`
  * URL: `https://storage.googleapis.com/did-cel-log/zW1b...`

Query-Based (Dynamic API):
  * `storage`: `https://example/didcel?msid=`
  * URL: `https://example/didcel?msid=zW1b...`

Native IPFS (Content-Addressable):
  * `storage`: `ipfs://bafybeigdy.../`
  * URL: `ipfs://bafybeigdy.../zW1b...`

#### DID URL Parameter

* **Key:** `storage`
* **Value:** A valid URI (typically `https://...`) pointing to a directory or service.
* **Resolution Rule:** The resolver appends the `method-specific-id` to the `storage` value to form the final fetch URL.


---

# `CellStorageService` | GCS

Manual provisioning of Google Cloud Storage resources to serve as the `CellStorageService`. This section describes the manual process for initializing storage and uploading the initial log.

## Creating the Storage Bucket
The bucket acts as the static repository for the DID Event Logs. A single bucket can host any number of `did:cel` logs as flat files.

1.  **Create Bucket:** Initialize a bucket in a preferred region.
    ```bash
    gcloud storage buckets create gs://[STORAGE] --location=[REGION]
    ```
2.  **Enable Public Access:**  
To prevent the enumeration of all DIDs stored within a registry, the storage bucket is configured to allow "Direct Object Fetch" while disabling "Bucket Listing."

  2.1.  **Selection of Role:** A custom IAM role (e.g., `celLogViewer`) is defined at the project level. This role must contain the `storage.objects.get` permission and must exclude the `storage.objects.list` permission.
  2.2.  **Configuration Command:**
   ```bash
   gcloud iam roles create celLogViewer \
        --project=[PROJECT_ID] \
        --title="did:cel Log Viewer" \
        --permissions=storage.objects.get
   ``` 
   ```bash
   gcloud storage buckets add-iam-policy-binding gs://[STORAGE] \
        --member="allUsers" \
        --role="projects/[PROJECT_ID]/roles/celLogViewer"
   ```
  2.3.  **Resulting Behavior:**
   * Authorized Access: `GET /[method-specific-id]` returns the log.
   * Unauthorized Discovery: `GET /` (root listing) returns a `403 Forbidden` response.
    
## Manual Log Upload
The initial log must be formatted as a JSON array containing the inception event ($E_0$) where the blob name is `method-specific-id`.

1.  **Naming Convention:** If the DID is `did:cel:zW1bVJv...`, the filename must be `zW1bVJv...`.
2.  **Upload Command:**
    ```bash
    gcloud storage cp log.json gs://[STORAGE]/[method-specific-id]
    ```
3.  **Metadata Configuration:** Ensure the `Content-Type` is set to `application/json` to prevent resolution errors during the fetch phase.
    ```bash
    gcloud storage objects update gs://[STORAGE]/[method-specific-id] \
        --content-type="application/json"
    ```

## Validation of Resolution

A resolver fetching the log receives a `200 OK` status with `Content-Type: application/json`.

1.  **Direct Fetch Test:**
    ```bash
    curl -H "Accept: application/json" -I https://storage.googleapis.com/[BUCKET]/[method-specific-id]
    ```

## DID URL Construction
Once uploaded, the `storage` parameter in the DID URL may point to the bucket's public storage base:
`did:cel:method-specific-id?storage=https://storage.googleapis.com/[BUCKET]/`

