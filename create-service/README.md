
## Service

#### Request

* `keyAlgorithm` KMS signing key algorithm, one of:

| Cryptosuite | `keyAlgorithm` | Key Size |
|-------------|------------------|--------|
| `ecdsa-jcs-2019` | `EC_SIGN_P256_SHA256` | 256 bits |
| `ecdsa-jcs-2019` | `EC_SIGN_P384_SHA384` | 384 bits |
| `eddsa-jcs-2022` | `EC_SIGN_ED25519` | 256 bits |
  
* `hms` optional, if `true` then Hardware Security Module  (HMS) is required (default `false`)
* `heartbeatFrequency` optional (default: P3M)

```bash
curl -X POST ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{"keyAlgorithm":"EC_SIGN_P256_SHA256"}'
```

```json
{
  "keyAlgorithm": "EC_SIGN_P256_SHA256",
  "hms": false,
  "heartbeatFrequency": "P3M"
}
```

#### Response

```
HTTP/1.1 201 Created
...
Location: https://storage.googleapis.com/BUCKET_NAME/DID_METHOD_SPECIFIC_ID
Content-Type: application/json

{
  Initial DID Log 
}
```

## Deploy


### Configuration

The service is configured via the following environment variables:

| Variable | Required | Description |
|----------|----------|------------|
| `KMS_LOCATION` | Yes | Google Cloud region where the KMS key is located (e.g., `us-central1`) |
| `KMS_KEY_RING` | Yes | Name of the Cloud KMS KeyRing |
| `BUCKET_NAME` |  Yes | Name of GCS bucket to store initial event logs |

### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="did:cel create"
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
* `roles/cloudkms.signer` (To sign and view public key)
* `roles/storage.objectCreator` (To store initial DID log on GCS)

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
  --role="roles/cloudkms.signer"
```

```bash
gcloud storage buckets add-iam-policy-binding gs://BUCKET_NAME \
    --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectCreator"
```
