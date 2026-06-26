#!/usr/bin/env bash
# Build + deploy the Heirloom pipeline to Cloud Run (L4, scale-to-zero).
# Run from this pipeline/ directory. gcloud must be authed on project bakers-agent.
#
# One-time: store the Replicate token in Secret Manager (used for the 4 public
# models — NOT a custom warm deploy):
#   printf '%s' 'r8_xxxxx' | gcloud secrets create replicate-token --data-file=-
#   gcloud secrets add-iam-policy-binding replicate-token \
#     --member="serviceAccount:$(gcloud projects describe bakers-agent --format='value(projectNumber)')-compute@developer.gserviceaccount.com" \
#     --role=roles/secretmanager.secretAccessor
set -euo pipefail
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-heirloom-pipeline}"

gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --gpu 1 --gpu-type nvidia-l4 --no-gpu-zonal-redundancy \
  --max-instances 1 --cpu 4 --memory 16Gi --no-cpu-throttling \
  --timeout 600 --port 8080 \
  --allow-unauthenticated \
  --set-secrets "REPLICATE_API_TOKEN=replicate-token:latest" \
  --quiet

echo
echo "URL: $(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')"
