# GCS `CelStorageService` Endpoint

Manual provisioning of Google Cloud Storage resources to serve as the `CelStorageService`. This section describes the manual process for initializing storage, automated management, and manual upload of the `did:cel` event log.

Although GCS provides high availability and durability, within the `did:cel` ecosystem it is recommended to distribute logs across a diverse set of storage provider to reduce reliance on any single infrastructure vendor. A GCS-backed `CelStorageService` can be one such provider, complementing others to improve redundancy, resilience, and data accessibility.

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
   * Public Access: `GET /[method-specific-id]` returns the log.
   * Unauthorized Discovery: `GET /` (root listing) returns a `403 Forbidden` response.
    
## Automated Log Management

TBD GC managed did:cel <-> KMS key pair
    
## Manual Log Upload
The `did:cel` event log must be formatted as a JSON array containing events ($E_0 \dots E_n$) where the blob name is `method-specific-id`.

1.  **Naming Convention:** If the DID is `did:cel:zW1bVJv...`, the blob name must be `zW1bVJv...`.
2.  **Upload Command:**
    ```bash
    gcloud storage cp my-did-cel-log.json gs://[STORAGE]/[method-specific-id]
    ```
3.  **Metadata Configuration:** Ensure the `Content-Type` is set to `application/json` to prevent resolution errors during the fetch phase.
    ```bash
    gcloud storage objects update gs://[STORAGE]/[method-specific-id] \
        --content-type="application/json"
    ```

## Manual Log Deletion
```bash
gcloud storage rm gs://[STORAGE]/[method-specific-id]
```

## Validation of Resolution

A resolver fetching the log receives a `200 OK` status with `Content-Type: application/json`.

  **Direct Fetch Test:**
  ```bash
  curl -H "Accept: application/json" -I https://storage.googleapis.com/[STORAGE]/[method-specific-id]
  ```

## DID URL Construction
Once uploaded, the `storage` parameter in the DID URL may point to the bucket's public storage base:
`did:cel:[method-specific-id]?storage=https://storage.googleapis.com/[STORAGE]/`
