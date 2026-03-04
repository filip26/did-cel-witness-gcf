
## Service

#### Request

* `keyAlgorithm` KMS signing key algorithm, one of:

| Cryptosuite | `keyAlgorithm` | Key Size |
|-------------|------------------|--------|
| `ecdsa-jcs-2019` | `EC_SIGN_P256_SHA256` | 256 bits |
| `ecdsa-jcs-2019` | `EC_SIGN_P384_SHA384` | 384 bits |
| `eddsa-jcs-2022` | `EC_SIGN_ED25519` | 256 bits |
  
* `hsm` optional, if `true` then Hardware Security Module  (HSM) is required (default `false`)
* `heartbeatFrequency` optional (default: P3M)

```bash
curl -X POST ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{"keyAlgorithm":"EC_SIGN_P256_SHA256"}'
```

```json
{
  "keyAlgorithm": "EC_SIGN_P256_SHA256",
  "hsm": false,
  "heartbeatFrequency": "P3M"
}
```

#### Response

```
HTTP/2 201 Created
location: https://storage.googleapis.com/BUCKET_NAME/DID_METHOD_SPECIFIC_ID
content-type: application/json
...

{
  Initial DID Log 
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
| `BUCKET_NAME` |  Yes | Name of GCS bucket to store event logs |

### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="did:cel creator"
```

Create a new role:

```bash
gcloud iam roles create kmsKeyCreator \
    --title="KMS Key Creator Minimal" \
    --description="Allows creating KMS keys." \
    --project=$PROJECT_ID
    --permissions="cloudkms.cryptoKeys.create,cloudkms.keyRings.get" \
    --stage="GA"
```

Grant these roles to the service account:

* `projects/$PROJECT_ID/roles/kmsKeyCreator` (To create a new key)
* `roles/cloudkms.publicKeyViewer` (To view a public key)
* `roles/cloudkms.signer` (To sign)
* `roles/storage.objectCreator` (To store initial`did:cel` event log on GCS)

```bash
gcloud kms keyrings add-iam-policy-binding $KMS_KEY_RING \
    --location=$KMS_LOCATION \
    --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
    --role="projects/$PROJECT_ID/roles/kmsKeyCreator"
```

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
