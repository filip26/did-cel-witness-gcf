#!/usr/bin/env bash

FUNCTION_NAME=$1

if [ -z "$FUNCTION_NAME" ]; then
  echo "Error: No function name provided."
  echo "Usage: ./purge-revisions.sh <function-name>"
  exit 1
fi

CONFIG_FILE=".env.json"

REGION=$(jq -r --arg NAME "$FUNCTION_NAME" '."witness-service"[] | select(.name == $NAME) | .region' "$CONFIG_FILE")
SA=$(jq -r --arg NAME "$FUNCTION_NAME" '."witness-service"[] | select(.name == $NAME) | .serviceAccount' "$CONFIG_FILE")

if [ "$SA" == "null" ] || [ "$REGION" == "null" ]; then
  echo "Error: Configuration for $FUNCTION_NAME not found."
  exit 1
fi

REVISIONS=$(gcloud run revisions list \
  --service=$FUNCTION_NAME \
  --region=$REGION \
  --sort-by="~metadata.creationTimestamp" \
  --format="value(metadata.name)" | tail -n +3)
    
for REV in $REVISIONS; do 
  gcloud run revisions delete $REV --region=$REGION --quiet
done

