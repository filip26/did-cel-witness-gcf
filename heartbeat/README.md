# Scheduled Heartbeat Events

The `did:cel` heartbeat event generator is implemented as a Google Cloud Function that can be scheduled via Google Cloud Scheduler. It uses Google Cloud KMS for secure key management and GCS to read and store the updated event log, providing a solution for managing `did:cel` identifiers on Google Cloud infrastructure. This setup ensures automated, periodic heartbeat events.
