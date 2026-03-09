# Icon `did:cel` Provision Service

Provisions a `did:cel` identifier by binding an existing Google Cloud KMS key. Initializes the corresponding `did:cel` event log, optionally storing it in GCS.

## Service

#### Request

- `assertionMethod` (required)  
  Identifier of the Google Cloud KMS signing key used as the `did:cel` assertion method.

  The key must use one of the following supported algorithms:

| Cryptosuite        | KMS Algorithm        | Key Size |
|--------------------|----------------------|----------|
| `ecdsa-jcs-2019`   | `EC_SIGN_P256_SHA256` | 256 bits |
| `ecdsa-jcs-2019`   | `EC_SIGN_P384_SHA384` | 384 bits |
| `eddsa-jcs-2022`   | `EC_SIGN_ED25519`     | 256 bits |

- `service` (required)  
  Defines service endpoints associated with the identifier.

- `heartbeatFrequency` (optional)  
  ISO-8601 duration specifying how often heartbeat events should be generated.  
  Default: `P3M`.

```json
{
  "heartbeatFrequency": "P3M",
  "assertionMethod": [{ 
  	"kmsKey": "...",
  	"kmsKeyVersion": "..."
  }],
  "authenticationMethod": [{

  }],

  "service": [{
  	  "type": "CelStorageService",
  	  "serviceEndpoint": [
  	     "https://storage.googleapis.com/did-cel-log/",
  	     "..."
  	  ]
     }
  }]
}
```

#### Response

```
HTTP/2 200 OK
content-type: application/json

{
  "keys": {
    "#key_id": "KMS_KEY_RESOURCE_PATH"
  },
  "log": Initial Event Log
}
```

## Deploy

### Configuration

The service is configured via the following environment variables:

| Variable | Required | Description |
|----------|----------|------------|
| `KMS_LOCATION` | Yes | Google Cloud region where the KMS key is located (e.g., `us-central1`) |
| `KMS_KEY_RING` | Yes | Name of the Cloud KMS KeyRing |

### IAM Permissions

Create a new service account:

```bash
gcloud iam service-accounts create SA-NAME \
    --display-name="did:cel provisioner"
```

Grant these roles to the service account:

* `roles/cloudkms.publicKeyViewer` (To view a public key)
* `roles/cloudkms.signer` (To sign)

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


