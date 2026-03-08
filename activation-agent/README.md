# Icon `did:cel` Activation Agent


## Service

#### Request


#### Response


## Deploy

### Configuration

The service is configured via the following environment variables:

| Variable | Required | Description |
|----------|----------|------------|
| `KMS_LOCATION` | Yes | Google Cloud region where the KMS key is located (e.g., `us-central1`) |
| `KMS_KEY_RING` | Yes | Name of the Cloud KMS KeyRing |
| `BUCKET_NAME` |  No | Name of GCS bucket to store event logs |

### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="did:cel provisioner"
```

Grant these roles to the service account:

* `roles/cloudkms.publicKeyViewer` (To view a public key)
* `roles/cloudkms.signer` (To sign)
* `roles/storage.objectCreator` (To store initial`did:cel` event log on GCS)

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

```bash
gcloud storage buckets add-iam-policy-binding gs://$BUCKET_NAME \
    --member="serviceAccount:SA-NAME@PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectCreator"
```

---


