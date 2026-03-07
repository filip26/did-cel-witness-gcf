# Icon `did:cel` Heartbeat Service

The `did:cel` heartbeat event generator is implemented as a Google Cloud Function that can be scheduled via Google Cloud Scheduler. It uses Google Cloud KMS for secure key management and GCS to read and store the updated event log, providing a solution for managing `did:cel` identifiers liveness and temporal continuity on Google Cloud infrastructure. This setup ensures automated, periodic heartbeat events.


gcloud services enable cloudscheduler.googleapis.com


gcloud scheduler jobs create pubsub daily-audit \
    --schedule="0 2 * * *" \
    --topic=run-metadata-topic \
    --message-body='{"runType": "AUDIT", "priority": "high"}'
    
gcloud scheduler jobs create pubsub weekly-cleanup \
    --schedule="0 0 * * 0" \
    --topic=run-metadata-topic \
    --message-body='{"runType": "CLEANUP", "retentionDays": 30}'    
    
gcloud functions deploy scheduled-metadata-task \
  --gen2 \
  --runtime=java25 \
  --trigger-topic=run-metadata-topic \
--trigger-topic=scheduled-runs \  
  --set-env-vars=JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:ZUncommitDelay=5 -XX:+CompactObjectHeaders"    