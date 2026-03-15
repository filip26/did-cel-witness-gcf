#!/usr/bin/env bash

FUNCTION_ID=$1

if [ -z "$FUNCTION_ID" ]; then
  echo "Error: No function id provided."
  echo "Usage: purge-revisions.sh <function-id | all>"
  exit 1
fi

CONFIG_FILE="functions.json"

if [ "$FUNCTION_ID" == "all" ]; then
  FUNCTIONS=$(jq -r --arg ID "$FUNCTION_ID" '.[] | .id' "$CONFIG_FILE")
else
  FUNCTIONS="$FUNCTION_ID"
fi


REVISIONS=""

for FNC in $FUNCTIONS; do

  REGION=$(jq -r --arg ID "$FNC" '.[] | select(.id == $ID) | .region' "$CONFIG_FILE")
  
  if [ -z "$REGION" ]; then
    echo "Error: Configuration region for $FNC not found."
    exit 1
  fi
  
  printf "%s\t%s\t" $FNC $REGION

  NR=$(gcloud run revisions list \
    --service=$FNC \
    --region=$REGION \
    --sort-by="~metadata.creationTimestamp" \
    --format="value(metadata.name)" | tail -n +3)

  if [ "$NR" != "" ]; then
    while read -r REV; do
      REVISIONS+="${REGION}|${REV}"$'\n'
    done <<< "$NR"

    printf "%s\n" $(wc -l <<< $NR)
  else 
    printf "OK\n"    
  fi
done
    
while IFS="|" read -r REGION REV; do
  if [ -z "$REGION" ]; then 
    continue 
  fi
  echo "gcloud run revisions delete "$REV" --region="$REGION" --quiet"
done <<< "$REVISIONS"
