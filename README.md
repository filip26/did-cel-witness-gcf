# Iron `did:cel` Agents, Services, and Storage on GC

An experimental, modular, composable implementation of an Oblivious Witness Service and `did:cel` identifiers managed by Google Cloud KMS.

This repository provides services, agents, and libraries for creating, managing, witnessing, and verifying `did:cel` event logs in a secure and privacy-preserving way.


# Oblivious Witness Service

[Oblivious Witness Service](./witness-service)

Performs oblivious witnessing of event log. Issues signed and timestamped attestations over event log hashes using Google Cloud KMS in a serverless environment. Processes only cryptographic hashes and never accesses event log contents, preserving privacy while producing verifiable VD DI witness proofs.

Can be used independently of the Iron `did:cel` ecosystem.

# Managed `did:cel` Identifiers

A set of services, agents, and libraries that use Google Cloud KMS to create and manage secure `did:cel` identifiers. Components can be used independently or together.

- [Create Service](./create-service) 
  Creates a new `did:cel` bound to Google KMS key.
- [Heartbeat Agent](./heartbeat-agent)
  Heartbeat event generator assuring liveness and temporal continuity of an event log.
- [Identity Agent](./identity-agent)
  Acts on behalf of a DID controller to prove DID ownership.
- Life-Cycle Listener
  Reflects changes on KMS keys bound to `did:cel` in the event log (TBD).
- Resolver
  Resolves a given `did:cel` and validates its event log to produce the DID document (TBD).
- [Storage Service](./storage-service)
  GCS utilized as `CelStorageService`.
- [Witness Agent](./witness-agent)
  Coordinates oblivious witnessing of `did:cel` event logs for identifiers managed using Google KMS and GCS.
- [Witness Verifier](./witness-verifier)
  Simple library for verifying VCDI witness proofs in O(1).

## 🤝 Contributing

Contributions of all kinds are welcome - whether it’s code, documentation, testing, or community support! Please open PR or issue to get started.

## 📚 Resources

- [The `did:cel` Method Specification](https://w3c-ccg.github.io/did-cel-spec/)
- [W3C Verifiable Credential Data Integrity](https://www.w3.org/TR/vc-data-integrity)

## 💼 Commercial Support

Commercial support and consulting are available.
For inquiries, please contact: filip26@gmail.com

