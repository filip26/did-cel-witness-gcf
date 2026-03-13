#!/usr/bin/env bash

FUNCTION_NAME=$1

if [ -z "$FUNCTION_NAME" ]; then
  echo "Error: No function name provided."
  echo "Usage: ./deploy.sh <function-name>"
  exit 1
fi

CONFIG_FILE=".env.json"

# Extract the specific witness object as a comma-separated list of KEY=VALUE
# This maps JSON keys to the Uppercase environment variables required by the function
ENV_VARS=$(jq -r --arg NAME "$FUNCTION_NAME" '
  ."witness-service"[] | select(.name == $NAME) | 
  [
    "KMS_LOCATION=" + .kmsLocation,
    "KMS_KEY_RING=" + .kmsKeyRing,
    "KMS_KEY_ID=" + .kmsKeyId,
    "C14N=" + .c14n,
    "VERIFICATION_METHOD=" + .verificationMethod
  ] | join(",")
' "$CONFIG_FILE")

REGION=$(jq -r --arg NAME "$FUNCTION_NAME" '."witness-service"[] | select(.name == $NAME) | .region' "$CONFIG_FILE")
SA=$(jq -r --arg NAME "$FUNCTION_NAME" '."witness-service"[] | select(.name == $NAME) | .serviceAccount' "$CONFIG_FILE")

if [ "$SA" == "null" ] || [ "$REGION" == "null" ] || [ -z "$ENV_VARS" ]; then
  echo "Error: Configuration for $FUNCTION_NAME not found."
  exit 1
fi


# JVM_OPTS Optimization Reasoning:
# -XX:+UseSerialGC: Minimizes CPU overhead and memory footprint in 1-vCPU environments.
# -Xss256k: Reduces thread stack size from default (usually 1MB) to save RAM.
# -XX:MaxRAMPercentage=80.0: Ensures JVM leaves 20% overhead for the OS/container to prevent OOM kills.
# -XX:TieredStopAtLevel=1: Disables C2 compiler to speed up startup/cold starts by using only C1.
JVM_OPTS="-XX:+UseSerialGC -Xss256k -XX:MaxRAMPercentage=80.0 -XX:TieredStopAtLevel=1"

gcloud functions deploy $FUNCTION_NAME \
  --gen2 \
  --region=$REGION \
  --runtime=java25 \
  --entry-point=WitnessService \
  --trigger-http \
  --source=./witness-service/. \
  --allow-unauthenticated \
  --service-account=witness-invoker@api-catalog.iam.gserviceaccount.com \
  --set-env-vars="$ENV_VARS,JAVA_TOOL_OPTIONS=$JVM_OPTS"
  