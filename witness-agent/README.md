# Iron `did:cel` Witness Agent

A service for coordinating oblivious witnessing of `did:cel` event logs for `did:cel`, using GCS as the event log storage.

## Service

#### Request

```json
{
	"id":"did:cel:zW1...",
	"witnessEndpoint":[
		"https://witness-red-5qnvfghl2q-uc.a.run.app", 
		"https://witness-blue-5qnvfghl2q-ey.a.run.app"
	]
}
```

## Deployment

### Configuration

The service is configured via the following environment variables:

| Variable | Required | Description |
|----------|----------|------------|
| `BUCKET_NAME` |  Yes | Name of GCS bucket |


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

