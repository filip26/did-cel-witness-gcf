# Iron `did:cel` Agents, Services, and Storage

An experimental, modular, composable implementation of an Oblivious Witness Service and `did:cel` identifiers managed by Google Cloud KMS.

This repository provides services, agents, and libraries for creating, managing, witnessing, and verifying `did:cel` event logs in a secure and privacy-preserving way.


## 🛡️ Oblivious Witness Service

[Oblivious Witness Service](./witness-service)

Performs oblivious witnessing of event log. Issues signed and timestamped attestations over event log hashes using Google Cloud KMS in a serverless environment. Processes only cryptographic hashes and never accesses event log contents, preserving privacy while producing verifiable W3C VC Data Integrity witness proofs. 

⚡ $O(1)$ c14n, supports RDFC or JCS ⚡

Can be used independently of the `did:cel` ecosystem.

## 🔐 Managed `did:cel` Identifiers

A modular suite for managing the lifecycle of secure `did:cel` identifiers using Google Cloud KMS. Components can be used independently or as a unified stack.

- [Create Service](./create-service) 
  Provisions a new KMS key as `did:cel` and initializes its corresponding event log.

- [Witness Agent](./witness-agent)
  Orchestrates the oblivious witnessing process for identifiers managed via KMS and GCS.

- [Heartbeat Service](./heartbeat-service)
  Generates periodic events to ensure liveness and temporal continuity of the event log.
  
- [Identity Agent](./identity-agent)
  Authorizes operations and proves `did:cel` ownership on behalf of the controller.
  
- **Adoption Service**
  Binds an existing KMS key to a new `did:cel` and initializes the initial event log.
  
- **Life-Cycle Listener**
  Reflects changes on KMS keys bound to `did:cel` in the event log (TBD).
  
- **Resolver**
  Resolves `did:cel` identifiers and validates the event log to assemble the DID Document (TBD).
  
- [Storage Service](./storage-service)
  Utilizes the GCS back-end for the logs as `CelStorageService`. 
    
- [Witness Verifier](./witness-verifier)
  Library for $O(1)$ verification of W3C VC Data Integrity witness proofs.

## 🤝 Contributing

Contributions of all kinds are welcome - whether it’s code, documentation, testing, or community support! Please open PR or issue to get started.

## 📚 Resources

- [The `did:cel` Method Specification](https://w3c-ccg.github.io/did-cel-spec/)
- [W3C Verifiable Credential Data Integrity](https://www.w3.org/TR/vc-data-integrity)

## 💼 Commercial Support

Commercial support and consulting are available.
For inquiries, please contact: filip26@gmail.com

