# `did:cel` Witness Service

A **did:cel witness service** performing **oblivious witnessing**, issuing signed and timestamped attestations over cryptographic event log hashes using **Cloud KMS** in a serverless function environment. Importantly, the service **never sees the event content**, preserving privacy while providing verifiable proofs.

## Overview

Witnesses provide **cryptographic proofs** that an event existed at a specific time without accessing the event itself. This ensures **privacy, auditability, and integrity** in `did:cel` event logs.

Key features:

- **Oblivious witnessing** – operates only on hashes; the witness cannot see the event content.  
- **Signed & timestamped attestations** – cryptographically verifiable proofs.  
- **Cloud KMS integration** – secure key management for signing.  
- **Serverless function** – scalable, low-overhead execution.

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
