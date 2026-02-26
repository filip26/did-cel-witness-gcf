# `did:cel` Witness Service

A did:cel witness service performing oblivious witnessing, issuing signed and timestamped attestations over cryptographic event log hashes using Cloud KMS in a serverless function environment. The service never sees the event content, preserving privacy while providing verifiable proofs.

## Overview

Witnesses provide cryptographic proofs that an event existed at a specific time without accessing the event itself. This ensures privacy, auditability, and integrity in `did:cel` event logs.

The canonicalization methods used (`JCS` or `RDFC`) are static, O(1). No JSON-LD, JCS, RDFC processing; the canonicalization is strictly performed on pre-computed canonical data structures.

### ✨ Features

- Oblivious witnessing - operates only on hashes; the witness cannot see the event content.  
- Signed & timestamped attestations - cryptographically verifiable proofs.  
- ⚡ Static O(1) c14n - supports RDFC or JCS
- Cloud KMS integration - secure key management for signing.  
- Serverless function - scalable, low-overhead execution.
- Self-Configuring - on cold start, the service fetches KMS metadata to automatically detect the algorithm and required key size.

## Test Endpoints

#### `ecdsa-jcs-2019`, `256bit`, `us-central1`, `HSM`

`https://us-central1-api-catalog.cloudfunctions.net/red-witness`

#### `eddsa-rdfc-2022`, `256bit`, `europe-west3`

TODO

## Service

* `digestMultibase` must contain canonical digest you want signed, witnessed.
* `digestMultibase` field must be multibase-encoded. Supported encodings:
  - Base58BTC
  - Base64 URL without padding
* The response will include the signed JSON proof from the witness service.


```bash
curl -X POST ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{"digestMultibase":"..."}'
```

#### Request

```json
{
  "digestMultibase": "z.."
}
```

#### Response

```javascript
{
  "type": "DataIntegrityProof",
  "cryptosuite": "...",
  "created": "2022-02-17T17:59:08Z",
  "nonce": "kiYZJHL...",
  "verificationMethod": "...",
  "proofPurpose": "assertionMethod",
  "proofValue": "zxwVk4..."
}
```

## Build & Deploy

### Prerequisites
* JDK 25
* Maven 3.9+
* [Google Cloud KMS Key](https://cloud.google.com/security/products/security-key-management) – Asymmetric Signing (EC or EdDSA).
* [Google Cloud SDK / gcloud CLI](https://cloud.google.com/sdk/docs/install)
* Configured GCP project

### Configuration

The service is configured via the following environment variables:

| Variable | Required | Description |
|----------|----------|------------|
| `KMS_LOCATION` | Yes | Google Cloud region where the KMS key is located (e.g., `us-central1`) |
| `KMS_KEY_RING` | Yes | Name of the Cloud KMS KeyRing |
| `KMS_KEY_ID` | Yes | Name of the Cloud KMS CryptoKey |
| `KMS_KEY_VERSION` | No | CryptoKey version to use (default: `1`) |
| `VERIFICATION_METHOD` | Yes | Verification method identifier (e.g., `did:example:123#key-1`) |
| `C14N` | Yes | Canonicalization method: `JCS` or `RDFC` |

### Supported Cryptosuites

The cryptosuite must match both the selected canonicalization method (`C14N`) and the KMS key algorithm.

| Cryptosuite | KMS Key Algorithm | `C14N` | Key Size |
|-------------|------------------|--------|----------|
| `ecdsa-jcs-2019` | `EC_SIGN_P256_SHA256` | `JCS` | 256 bits |
| `ecdsa-jcs-2019` | `EC_SIGN_P384_SHA384` | `JCS` | 384 bits |
| `eddsa-jcs-2022` | `EC_SIGN_ED25519` | `JCS` | 256 bits |
| `ecdsa-rdfc-2019` | `EC_SIGN_P256_SHA256` | `RDFC` | 256 bits |
| `ecdsa-rdfc-2019` | `EC_SIGN_P384_SHA384` | `RDFC` | 384 bits |
| `eddsa-rdfc-2022` | `EC_SIGN_ED25519` | `RDFC` | 256 bits |

#### Notes

- The KMS key must be created with a signing algorithm that is supported.
- `JCS` refers to JSON Canonicalization Scheme.
- `RDFC` refers to RDF Dataset Canonicalization.

### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="Witness Function Invoker"
```

```bash
gcloud functions add-invoker-policy-binding FUNCTION_NAME \
    --region=REGION \
    --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com"
```

Grant these roles to the service account:

* `roles/cloudkms.signer` (To sign)
* `roles/cloudkms.viewer` (To detect key size/algorithm during cold-start)

```bash
gcloud kms keys add-iam-policy-binding $KMS_KEY_ID \
  --location=$KMS_LOCATION \
  --keyring=$KMS_KEY_RING \
  --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudkms.signer"
```

```bash
gcloud kms keys add-iam-policy-binding $KMS_KEY_ID \
  --location=$KMS_LOCATION \
  --keyring=$KMS_KEY_RING \
  --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudkms.viewer"
```
  
### Build
```bash
mvn clean package
```
  
### Deployment
 
```bash
 gcloud functions deploy [FUNCTION_NAME] \
  --gen2 \
  --runtime=java25 \
  --source=. \
  --entry-point=WitnessService \
  --trigger-http \
  --service-account=SA-NAME@PROJECT_ID.iam.gserviceaccount.com
  --set-env-vars="KMS_LOCATION=$KMS_LOCATION,KMS_KEY_RING=$KMS_KEY_RING,KMS_KEY_ID=$KMS_KEY_ID,C14N=$C14N,VERIFICATION_METHOD=$VERIFICATION_METHOD"
```

If deploying a public witness service without authentication, you can add the flag `--allow-unauthenticated`.

## Test

* Replace the URLs with your deployed function endpoints
* `digestMultibase` must contain canonical digest you want signed, witnessed.
* `digestMultibase` field must be multibase-encoded. Supported encodings:
  - Base58BTC
  - Base64 URL without padding
* Send a POST request with the digest to the deployed Cloud Function:

```bash
curl -X POST https://REGION-PROJECT_ID.cloudfunctions.net/witness \
  -H "Content-Type: application/json" \
  -d '{"digestMultibase":"..."}'
```

## Verify

* Combine request data and response data into a single signed JSON document.

```json
{
  "digestMultibase": "z..",
  "proof": {
	  "type": "DataIntegrityProof",
	  "cryptosuite": "...",
      "created": "2022-02-17T17:59:08Z",
      "nonce": "kiYZJHL...",	  
	  "verificationMethod": "did:...",	  
	  "proofPurpose": "assertionMethod",
	  "proofValue": "zxwVk4..."
  }
}
```

* The `digestMultibase` must match the digest of the content you signed.  
* `verificationMethod` must point to the correct public key (DID or KMS reference) used for signing.  
* All fields in the `proof` object must remain unchanged for the verification to succeed.  
* Use a Verifiable Credentials / Data Integrity (VC DI) verifier to validate the signed JSON proof.  

