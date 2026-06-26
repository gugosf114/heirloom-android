#!/usr/bin/env bash
# Build + deploy the Heirloom pipeline to Cloud Run (L4, scale-to-zero).
# Run from this pipeline/ directory. gcloud must be authed on project bakers-agent.
# Fully local pipeline — no external API, no secrets to set.
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
  --quiet

echo
echo "URL: $(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')"
