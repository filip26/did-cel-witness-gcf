
_The `did:cel` resolution, the read method is unofficial and provided for experimental purposes._

## Resolution (Read)

The resolution of a `did:cel` identifier is the process of retrieving and validating its event log to produce a compliant DID Document. This method is self-certifying and registry-agnostic, relying on the cryptographic integrity of the log's inception.

### Hybrid Discovery
The `did:cel` method supports a hybrid discovery model. While the `storage` parameter provides a high-performance, deterministic path for resolution, it does not represent a centralized point of failure, the identity is content-addressable, the same log can be hosted across multiple providers, peer-to-peer networks. 

### Algorithm
To resolve a `did:cel` identifier, a resolver MUST perform the following steps:

1. Extract the Commitment: Parse the `method-specific-id = multibase(base58btc, initialDidDocumentHash)` from the `did:cel` to obtain `initialDidDocumentHash`. 
2. Locate the Log: Retrieve the Event Log array from a distributed registry or a location specified by the `storage` parameter. If a `storage` URL is provided, the resolver MAY fetch the resource at `[URL][method-specific-id]`.
3. Verify Inception:
   1. Extract the create event log entry.
   2. Extract `didDocument` from the create event.
   3. The `didDocument.id` and `didDocument.assertionMethod.controller` fields MUST exactly match the `did:cel` which is being resolved.
   4. Recreate `initialDidDocument` by removing the `id` and `assertionMethod.controller` fields from the `didDocument`
   5. Perform `multihash(sha3-256(JCS(initialDidDocument)))`. The result value MUST exactly match the `initialDidDocumentHash` extracted from the DID.
5. Verify Integrity: Iterate through subsequent events ($E_n \dots E_0$), in reverse chronological order, starting with the newest entry, allowing previous verification to be reused if already cached or computed as needed. For each event, verify that:
    - For $E_n$ where $n \gt 1$, the `previousEventHash` MUST match the `sha3-256` hash of the previous event's document hash.
    - The event is signed by a key authorized in the state established by the previous event.
    - Witness Verification: The resolver MUST verify that the event contains a sufficient number of valid witness signatures. The specific threshold and selection of required witnesses are determined by application-level logic based on the trust requirements of the relying party.
6. Verify Liveness & Temporal Continuity: The resolver MUST verify a contiguous chain of heartbeat proofs throughout the log duration. 
    - Liveness: The last event log MUST occur within the period defined in the last effective `didDocument.heartbeatFrequency`.
    - Continuity: Any gap in the heartbeat chain that exceeds the allowed threshold—without an accompanying deactivation or authorized suspension event—MUST result in a validation failure. This ensures that a storage provider cannot omit intermediate events or "freeze" the state in the past.
7. Project State: Apply the cumulative state changes defined in the verified log to construct the final DID Document.
8. Verify Origin: 
    - If the event log was retrieved by using a provided `storage` URL parameter, then that exact URL MUST be listed as an approved `CelStorageService` within the service section of the assembled DID Document.

### Immutability and Caching

The `did:cel` event log is a cryptographically immutable ledger. Because each event $E_n$ is linked via the hash of its predecessor $E_{n-1}$, the log functions as a tamper-evident chain. 

Resolvers SHOULD cache verified events, event logs, locally. Once an event is validated against the inception commitment and the chain of signatures, it never needs to be re-verified or re-fetched.

### `storage`, `CelStorageService`, and URL Construction

The `did:cel` resolver uses a simple string concatenation rule to find logs. The final fetch URL is formed by appending the `method-specific-id` directly to the storage URL.

**Examples**

Path-Based (Static Hosting):
  * `storage`: `https://storage.googleapis.com/did-cel-log/`
  * URL: `https://storage.googleapis.com/did-cel-log/zW1b...`

Query-Based (Dynamic API):
  * `storage`: `https://example/didcel?msid=`
  * URL: `https://example/didcel?msid=zW1b...`

Native IPFS (Content-Addressable):
  * `storage`: `ipfs://bafybeigdy.../`
  * URL: `ipfs://bafybeigdy.../zW1b...`

#### DID URL Parameter

* **Key:** `storage`
* **Value:** A valid URI (typically `https://...`) pointing to a directory or service.
* **Resolution Rule:** The resolver appends the `method-specific-id` to the `storage` value to form the final fetch URL.
