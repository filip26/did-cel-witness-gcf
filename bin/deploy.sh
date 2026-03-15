#!/usr/bin/env bash

read -r -d '' USER_DATA <<'DATA'
WitnessService|./witness-service/.|--trigger-http
ProvisionService|./provision-service/.|--trigger-http
WitnessAgent|./witness-agent/.|--trigger-http
HeartbeatService|./heartbeat-service/.|--trigger-http
DATA

FUNCTION_ID=$1
CONFIG_FILE="functions.json"

if [ -z "$FUNCTION_ID" ]; then
  echo "Error: No function id provided."
  echo "Usage: deploy.sh <function-id>"
  exit 1
fi

# This maps JSON keys to the Uppercase environment variables required by the function
ENV_VARS=$(jq -r --arg ID $FUNCTION_ID '
  .[] | select(.id == $ID) | 
  .env | to_entries | 
  map("\(.key + "=" + (.value|tostring))" ) | 
  join(",") 
' $CONFIG_FILE)

while IFS='=' read -r k v; do
  export "$k=$v"
done < <(
  jq -r --arg ID "$FUNCTION_ID" '
    .[] | select(.id == $ID) | 
    with_entries(select(.key|(contains("env") | not))) | to_entries[] |
    "FNC_\(.key|ascii_upcase)=\(.value|tostring)"
  ' $CONFIG_FILE
)

if [ -z "$FNC_TYPE" ] || [ -z "$FNC_REGION" ] || [ -z "$FNC_SERVICEACCOUNT" ] || [ -z "$ENV_VARS" ]; then
 echo "Error: Configuration for $FUNCTION_ID not found."
 exit 1
fi

getFncArgs() {
  local id="$1"

  while IFS='|' read -r name source rest; do
    [[ "$name" == "$id" ]] || continue

    shift 0
    local args=()

    IFS='|' read -ra args <<< "$rest"

    printf " --source=%s" "$source"
    for a in "${args[@]}"; do
      printf " %s" "$a"
    done

    echo
    return 0
  done <<< "$USER_DATA"

  return 1
}

GC_ARGS=(functions deploy $FUNCTION_ID  
  --gen2 
  --runtime=java25 
  --region=$FNC_REGION 
  --entry-point=$FNC_TYPE 
  --service-account=$FNC_SERVICEACCOUNT 
  $(getFncArgs $FNC_TYPE) 
  $FNC_OPTIONS
  --set-env-vars="${ENV_VARS},JAVA_TOOL_OPTIONS=${FNC_JVM}"
)

echo gcloud "${GC_ARGS[@]}"
gcloud "${GC_ARGS[@]}"