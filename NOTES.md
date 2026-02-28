## Resolution (Read)

The resolution of a `did:cel` identifier is the process of retrieving and validating its **DID Event Log (DEL)** to produce a compliant DID Document. This method is self-certifying and registry-agnostic, relying on the cryptographic integrity of the log's inception.

### Resolution Algorithm
To resolve a `did:cel` identifier, a resolver MUST perform the following steps:

1.  Extract the Commitment: Parse the `method-specific-id` from the DID string to obtain the expected Genesis Hash.
2.  Locate the Log: Retrieve the Event Log array from a Verifiable Data Registry or a location specified by the `storage` parameter. If a `storage` URL is provided, the resolver MUST fetch the resource at `[URL]/[method-specific-id].json`.
3.  Verify Inception: Perform a JCS (JSON Canonicalization Scheme) serialization of the first entry (E_0) in the log. The `sha3-256` hash of this value MUST exactly match the Genesis Hash extracted from the DID.
4.  Validate Chain Integrity: Iterate through subsequent events ($E_1 \dots E_n$). For each event, verify that:
    * The `predecessor` field matches the hash of the previous event's JCS representation.
    * The event is signed by a key authorized in the state established by the previous event.
5.  Project State: Apply the cumulative state changes (key additions, rotations, or service updates) defined in the verified log to construct the final DID Document.



### Storage Parameter
The `storage` parameter MAY be used to provide a hint to the resolver regarding the location of the log file. 

* **Key:** `storage`
* **Value:** A valid URI (typically `https`) pointing to a directory or service.
* **Resolution Rule:** The resolver appends the `method-specific-id` and the `.json` extension to the `storage` value to form the final fetch URL.

## 6. Infrastructure and Manual Setup (GCloud)

For implementations leveraging the `storage` parameter, Google Cloud Storage (GCS) provides a highly available and globally distributed Verifiable Data Registry. This section describes the manual process for initializing storage and uploading the initial log.

### 6.1 Creating the Storage Bucket
The bucket acts as the static repository for the DID Event Logs. A single bucket can host any number of `did:cel` logs as flat files.

1.  **Create Bucket:** Initialize a bucket in a preferred region.
    ```bash
    gcloud storage buckets create gs://did-cel-registry --location=[REGION]
    ```
2.  **Enable Public Access:** Grant `allUsers` read permissions to allow the `Read` method to function without authentication.
    ```bash
    gcloud storage buckets add-iam-policy-binding gs://did-cel-registry \
        --member="allUsers" \
        --role="roles/storage.objectViewer"
    ```

### 6.2 Manual Log Upload
The initial log must be formatted as a JSON array containing the inception event ($E_0$). The filename MUST match the `method-specific-id` (the Genesis Hash) with a `.json` extension.

1.  **Naming Convention:** If the DID is `did:cel:zW1bVJv...`, the filename must be `zW1bVJv....json`.
2.  **Upload Command:**
    ```bash
    gcloud storage cp log.json gs://did-cel-registry/[method-specific-id].json
    ```
3.  **Metadata Configuration:** Ensure the `Content-Type` is set to `application/json` to prevent resolution errors during the fetch phase.
    ```bash
    gcloud storage objects update gs://did-cel-registry/[method-specific-id].json \
        --content-type="application/json"
    ```

### 6.3 Resolution URI Construction
Once uploaded, the `storage` parameter in the DID URL should point to the bucket's public storage base:
`did:cel:[suffix]?storage=https://storage.googleapis.com/did-cel-registry/`

### 6.4 Privacy-Preserving Access Control
To prevent the enumeration of all DIDs stored within a registry, the storage bucket MUST be configured to allow "Direct Object Fetch" while disabling "Bucket Listing."

1.  **Selection of Role:** A custom IAM role (e.g., `celLogReader`) SHOULD be defined at the project level. This role MUST contain the `storage.objects.get` permission and MUST exclude the `storage.objects.list` permission.
2.  **Configuration Command:**
    ```bash
    gcloud iam roles create celLogReader \
        --project=[PROJECT_ID] \
        --title="did:cel Log Reader" \
        --permissions=storage.objects.get

    gcloud storage buckets add-iam-policy-binding gs://did-cel-storage \
        --member="allUsers" \
        --role="projects/[PROJECT_ID]/roles/celLogReader"
    ```
3.  **Resulting Behavior:**
    * **Authorized Access:** `GET /[DID_SUFFIX].json` returns the log.
    * **Unauthorized Discovery:** `GET /` (root listing) returns a `403 Forbidden` response.
    
## 7. Validation of Resolution

To ensure the storage and resolution are correctly configured, the following validation steps SHOULD be performed.

### 7.1 Response Verification
A compliant resolver fetching the log MUST receive a `200 OK` status with `Content-Type: application/json`.

1.  **Direct Fetch Test:**
    ```bash
    curl -H "Accept: application/json" -I [https://storage.googleapis.com/](https://storage.googleapis.com/)[BUCKET]/[DID_SUFFIX].json
    ```

### 7.2 Cryptographic Integrity Check
The validation of the "Read" method is strictly cryptographic. A successful resolution MUST satisfy:
1.  **Hash Equality:** `sha3-256(JCS(Log[0]))` equals the `method-specific-id`.
2.  **Signature Validity:** The signature on the most recent event in the log corresponds to a public key defined in the active state.

### 7.3 DID Document Projection
The resulting DID Document MUST contain an `id` field exactly matching the input `did:cel` string, including the `storage` parameter used for discovery, as this parameter is part of the identifier's resolvable URL.