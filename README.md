# `did:cel` Witness Service

A **did:cel witness service** performing **oblivious witnessing**, issuing signed and timestamped attestations over cryptographic event log hashes using **Cloud KMS** in a serverless function environment. Importantly, the service **never sees the event content**, preserving privacy while providing verifiable proofs.

## Overview

Witnesses provide **cryptographic proofs** that an event existed at a specific time without accessing the event itself. This ensures **privacy, auditability, and integrity** in `did:cel` event logs.

### ✨ Features

- Oblivious witnessing – operates only on hashes; the witness cannot see the event content.  
- Signed & timestamped attestations – cryptographically verifiable proofs.  
- Cloud KMS integration – secure key management for signing.  
- Serverless function – scalable, low-overhead execution.
- Self-Configuring – on cold start, the service fetches KMS metadata to automatically detect the algorithm and required key size.

## Service

### Request

```json
{
  "digestMultibase": "z.."
}
```

### Response

```javascript
{
  "type": "DataIntegrityProof",
  "cryptosuite": "...", // eddsa-jcs-2022 or ecdsa-jcs-2019
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

- The selected **cryptosuite**, **canonicalization method (`C14N`)**, and **KMS key algorithm** must be compatible.
- `JCS` refers to JSON Canonicalization Scheme.
- `RDFC` refers to RDF Dataset Canonicalization.
- The KMS key must be created with a signing algorithm that matches the selected cryptosuite.
### IAM Permissions
Grant these roles to the service account:
1. `roles/cloudkms.signer` (To sign)
2. `roles/cloudkms.viewer` (To detect key size/algo during initialization)

```bash
gcloud kms keys add-iam-policy-binding $KMS_KEY_ID \
  --location=$KMS_LOCATION \
  --keyring=$KMS_KEY_RING \
  --member="serviceAccount:[SA_EMAIL]" \
  --role="roles/cloudkms.signer"
```

```bash
gcloud kms keys add-iam-policy-binding $KMS_KEY_ID \
  --location=$KMS_LOCATION \
  --keyring=$KMS_KEY_RING \
  --member="serviceAccount:[SA_EMAIL]" \
  --role="roles/cloudkms.viewer"
```
  
### Build
```bash
mvn clean package
```
  
### Deployment
 
```bash
 gcloud functions deploy witness-service \
  --gen2 \
  --runtime=java25 \
  --entry-point=WitnessService \
  --trigger-http \
  --set-env-vars="KMS_LOCATION=$KMS_LOCATION,KMS_KEY_RING=$KMS_KEY_RING,KMS_KEY_ID=$KMS_KEY_ID,C14N=$C14N,VERIFICATION_METHOD=$VERIFICATION_METHOD"
```

## Verify

Combine request data and response data into a single signed JSON document.

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
