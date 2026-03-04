# `did:cel` Witness Agent

Service for coordinating oblivious witnessing of did:cel event logs.

## Deployment

### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="Witness Agent"
```

Grant these roles to the service account:

* `roles/storage.objectUser` (To read and update `did:cel` event log on GCS)

```bash

```bash
gcloud storage buckets add-iam-policy-binding gs://$BUCKET_NAME \
    --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectUser"
```


### Deploy

```bash
gcloud functions deploy witness-agent \
    --runtime=java25 \
    --trigger-http \
    --entry-point=WitnessAgent \
    --concurrency=100 \
    --cpu=1 \
    --memory=256Mi \
    --service-account=SA-NAME@PROJECT_ID.iam.gserviceaccount.com \
    --set-env-vars="BUCKET_NAME=$BUCKET_NAME"
```
