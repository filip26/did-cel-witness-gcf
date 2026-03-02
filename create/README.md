
## Service

* `keyAlgorithm` KMS signing key algorithm, one of:
  | Cryptosuite | `keyAlgorithm` | Key Size |
  |-------------|------------------|--------|----------|
  | `ecdsa-jcs-2019` | `EC_SIGN_P256_SHA256` | 256 bits |
  | `ecdsa-jcs-2019` | `EC_SIGN_P384_SHA384` | 384 bits |
  | `eddsa-jcs-2022` | `EC_SIGN_ED25519` | 256 bits |
* `hms` optional, if `true` then Hardware Security Module  (HMS) is required


#### Request

```bash
curl -X POST URL \
  -H "Content-Type: application/json" \
  -d '{"keyAlgorithm":"EC_SIGN_P256_SHA256"}'
```

```json
{
  "keyAlgorithm": "EC_SIGN_P256_SHA256"
}
```

#### Response

TBD 

```javascript
{

}
```

## Deploy


### Configuration

The service is configured via the following environment variables:

| Variable | Required | Description |
|----------|----------|------------|
| `KMS_LOCATION` | Yes | Google Cloud region where the KMS key is located (e.g., `us-central1`) |
| `KMS_KEY_RING` | Yes | Name of the Cloud KMS KeyRing |
| `KMS_KEY_ALGO` | Yes | KMS signing key algorithm |

**Supported Key Types**

| Cryptosuite | KMS Key Algorithm | Key Size |
|-------------|------------------|--------|----------|
| `ecdsa-jcs-2019` | `EC_SIGN_P256_SHA256` | 256 bits |
| `ecdsa-jcs-2019` | `EC_SIGN_P384_SHA384` | 384 bits |
| `eddsa-jcs-2022` | `EC_SIGN_ED25519` | 256 bits |

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
    --description="Allows creating keys within a specific keyring only." \
    --project=$PROJECT_ID
    --permissions="cloudkms.cryptoKeys.create,cloudkms.keyRings.get" \
    --stage="GA"
```

Grant these roles to the service account:

* `projects/$PROJECT_ID/roles/kmsKeyCreator` (To create a new key)
* `roles/cloudkms.publicKeyViewer` (To view public key)
* `roles/cloudkms.signer` (To sign)
* `roles/cloudkms.viewer` (To detect key size/algorithm during cold-start)

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
gcloud kms keys add-iam-policy-binding $KMS_KEY_ID \
  --location=$KMS_LOCATION \
  --keyring=$KMS_KEY_RING \
  --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudkms.viewer"
```
    