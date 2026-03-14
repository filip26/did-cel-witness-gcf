# Icon `did:cel` Heartbeat Service

The `did:cel` heartbeat event generator is implemented as a Google Cloud Function that can be scheduled via Google Cloud Scheduler. It uses Google Cloud KMS for secure key management and GCS to read and store the updated event log, providing a solution for managing `did:cel` identifiers liveness and temporal continuity on Google Cloud infrastructure. This setup ensures automated, periodic heartbeat events.

### Request

```json
[{
  "id": "did:cel:zW1...",
  "key": "kms:KMS_KEY_ID/cryptoKeyVersions/KMS_KEY_VERSION",
  "verificationMethod": "#key-123",
  "witnessEndpoint":[
    "https://witness-red-5qnvfghl2q-uc.a.run.app", 
    "https://witness-blue-5qnvfghl2q-ey.a.run.app"
  ]}, {
 
}]
```


### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="did:cel heartbeater"
```

Grant these roles to the service account:

* `roles/storage.objectUser` (To read and update `did:cel` event log on GCS)
* `roles/cloudkms.publicKeyViewer` (To detect key algorithm)
* `roles/cloudkms.signer` (To sign)

```bash
gcloud storage buckets add-iam-policy-binding gs://$BUCKET_NAME \
    --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectUser"
```

```bash
gcloud kms keyrings add-iam-policy-binding $KMS_KEY_RING \
  --location=$KMS_LOCATION \
  --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudkms.publicKeyViewer"
```

```bash
gcloud kms keyrings add-iam-policy-binding $KMS_KEY_RING \
  --location=$KMS_LOCATION \
  --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudkms.signer"
```

---


```bash
gcloud services enable cloudscheduler.googleapis.com
```

```bash
gcloud scheduler jobs create pubsub JOB_NAME \
    --schedule="0 0 0 * *" \
    --topic=heartbeat \
    --location="" \
    --message-body='["did:cel:...","..."]'
```

```bash
gcloud functions deploy scheduled-metadata-task \
  --gen2 \
  --runtime=java25 \
  --trigger-topic=heartbeat \  
  --set-env-vars=JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:ZUncommitDelay=5 -XX:+CompactObjectHeaders"    
```