# Icon `did:cel` Provision Service

Provisions a `did:cel` identifier by binding an existing Google Cloud KMS key. Initializes the corresponding `did:cel` event log, optionally storing it in GCS.

## Service

#### Request

#### Parameters

- `assertionMethod` (required)  
  Identifier of the Google Cloud KMS signing key used as the `did:cel` assertion method.

  The key must use one of the following supported algorithms:

| Cryptosuite        | KMS Algorithm        | Key Size |
|--------------------|----------------------|----------|
| `ecdsa-jcs-2019`   | `EC_SIGN_P256_SHA256` | 256 bits |
| `ecdsa-jcs-2019`   | `EC_SIGN_P384_SHA384` | 384 bits |
| `eddsa-jcs-2022`   | `EC_SIGN_ED25519`     | 256 bits |

- `service` (required)  
  Defines service endpoints associated with the identifier.

  If a `gs://` URI is present, the initial event log is written to the specified
  Google Cloud Storage location and the response returns `201 Created`
  with the log location.

- `heartbeatFrequency` (optional)  
  ISO-8601 duration specifying how often heartbeat events should be generated.  
  Default: `P3M`.


```bash
curl -X POST ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{"assertionMethod": "KMS_KEY_ID"}'
```

```json
{
  "heartbeatFrequency": "P3M",
  "assertionMethod": "KMS_KEY_ID",
  "authenticationMethod": [{
	...
  }],
  ...
  "service": [
  	"gs://BUCKET_NAME/",
  	{
    ...
  }]
}
```


#### Response

If the initial event log is stored in Google Cloud Storage:

```
HTTP/2 201 Created
location: https://storage.googleapis.com/BUCKET_NAME/DID_METHOD_SPECIFIC_ID
content-type: application/json
```

Otherwise:

```
HTTP/2 200 OK
content-type: application/json
```

Response body:

```
{
  Initial Event Log
}
```

## Deploy

### KMS Pricing (March 2026)

| Algorithm | Protection Level | Cost per Month | Compliance |
| :--- | :--- | :--- | :--- |
| P-256 / P-384 / Ed25519 | Software | $0.06 | FIPS 140-2 Level 1 |
| P-256 / P-384 | HSM | $2.50 | FIPS 140-2 Level 3 |

_Note: Prices current as of March 4, 2026. Costs are per active key version._

### Configuration

The service is configured via the following environment variables:

| Variable | Required | Description |
|----------|----------|------------|
| `KMS_LOCATION` | Yes | Google Cloud region where the KMS key is located (e.g., `us-central1`) |
| `KMS_KEY_RING` | Yes | Name of the Cloud KMS KeyRing |
| `BUCKET_NAME` |  No | Name of GCS bucket to store event logs |

### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="did:cel provisioner"
```

Grant these roles to the service account:

* `roles/cloudkms.publicKeyViewer` (To view a public key)
* `roles/cloudkms.signer` (To sign)
* `roles/storage.objectCreator` (To store initial`did:cel` event log on GCS)

```bash
gcloud kms keyrings add-iam-policy-binding $KMS_KEY_RING \
  --location=$KMS_LOCATION \
  --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudkms.publicKeyViewer"
```

```bash
gcloud kms keyrings add-iam-policy-binding $KMS_KEY_RING \
  --location=$KMS_LOCATION \
  --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudkms.signer"
```

```bash
gcloud storage buckets add-iam-policy-binding gs://$BUCKET_NAME \
    --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectCreator"
```

---


