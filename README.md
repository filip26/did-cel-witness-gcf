# `did:cel` Witness Service

A **did:cel witness service** performing **oblivious witnessing**, issuing signed and timestamped attestations over cryptographic event log hashes using **Cloud KMS** in a serverless function environment. Importantly, the service **never sees the event content**, preserving privacy while providing verifiable proofs.

## Overview

Witnesses provide **cryptographic proofs** that an event existed at a specific time without accessing the event itself. This ensures **privacy, auditability, and integrity** in `did:cel` event logs.

### ✨ Features

- Oblivious witnessing – operates only on hashes; the witness cannot see the event content.  
- Signed & timestamped attestations – cryptographically verifiable proofs.  
- Cloud KMS integration – secure key management for signing.  
- Serverless function – scalable, low-overhead execution.
  * Self-Configuring – On cold start, the service fetches KMS metadata to automatically detect the algorithm and required key size.
  * Validation – Strictly enforces byte-length requirements (e.g., 32 bytes for Ed25519/P-256) before hitting the KMS API.
  * Efficiency – Uses streaming to minimize memory allocation and `static final` constants for O(1) jump-table execution.


## HTTP Service

### Request

```json
{
  "digestMultibase": "z..",
}
```
or 
```json
{
  "@value": "...",
  "@type":  "..."
}
```
where `@type` is one of
* `https://w3id.org/security#multibase`
* `http://www.w3.org/2001/XMLSchema#base64Binary`

### Response

```json
{
  "type": "ObliviousSignatureProof",
  "crypto": "ecdsa",
  "created": "2025-12-06T22:09:08Z",
  "proofPurpose": "assertionMethod",
  "proofValue": "zxwVk4...",
  "verificationMethod": "did:cel:zW1poy..."
}
```

## Prerequisites
* JDK 25
* Maven 3.9+
* KMS Key – Asymmetric Signing (EC or EdDSA).

## Configuration
The following environment variables are required:

| Variable | Description |
| :--- | :--- |
| `KMS_LOCATION` | GCP Region (e.g., `us-central1`) |
| `KMS_KEY_RING` | Name of the KMS KeyRing |
| `KMS_KEY_ID` | Name of the CryptoKey |
| `KMS_VERSION` | Key version (default: `1`) |

## IAM Permissions
Grant these roles to the service account:
1. `roles/cloudkms.signer` (To sign)
2. `roles/cloudkms.viewer` (To detect key size/algo during initialization)

```bash
gcloud kms keys add-iam-policy-binding [KEY_ID] \
  --location=[LOC] --keyring=[RING] \
  --member="serviceAccount:[SA_EMAIL]" \
  --role="roles/cloudkms.signer"

gcloud kms keys add-iam-policy-binding [KEY_ID] \
  --location=[LOC] --keyring=[RING] \
  --member="serviceAccount:[SA_EMAIL]" \
  --role="roles/cloudkms.viewer"
  
  

