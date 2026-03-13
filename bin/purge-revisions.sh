#!/usr/bin/env bash

FUNCTION_ID=$1

if [ -z "$FUNCTION_ID" ]; then
  echo "Error: No function id provided."
  echo "Usage: ./purge-revisions.sh <function-id>"
  exit 1
fi

CONFIG_FILE=".env.json"

REGION=$(jq -r --arg ID "$FUNCTION_ID" '.[] | select(.id == $ID) | .region' "$CONFIG_FILE")

if [ "$REGION" == "null" ]; then
  echo "Error: Configuration for $FUNCTION_ID not found."
  exit 1
fi

REVISIONS=$(gcloud run revisions list \
  --service=$FUNCTION_ID \
  --region=$REGION \
  --sort-by="~metadata.creationTimestamp" \
  --format="value(metadata.name)" | tail -n +3)
    
for REV in $REVISIONS; do 
  gcloud run revisions delete $REV --region=$REGION --quiet
done

