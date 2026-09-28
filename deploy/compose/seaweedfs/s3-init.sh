#!/usr/bin/env bash
# Prepares the raw zone bucket (DOC-39 §3.5, DOC-18 §1.4). Idempotent.
set -euo pipefail

ENDPOINT="${S3_ENDPOINT:-http://seaweedfs:8333}"
export AWS_DEFAULT_REGION=us-east-1

admin() { AWS_ACCESS_KEY_ID="$S3_ADMIN_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$S3_ADMIN_SECRET_KEY" aws --endpoint-url "$ENDPOINT" "$@"; }
etl() { AWS_ACCESS_KEY_ID="$S3_ETL_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$S3_ETL_SECRET_KEY" aws --endpoint-url "$ENDPOINT" "$@"; }

# 1. Bucket
if admin s3api head-bucket --bucket raw 2>/dev/null; then
  echo "Bucket raw already exists"
else
  admin s3api create-bucket --bucket raw
  echo "Created bucket raw"
fi

# 2. Versioning
admin s3api put-bucket-versioning --bucket raw --versioning-configuration Status=Enabled
[[ "$(admin s3api get-bucket-versioning --bucket raw --query Status --output text)" == "Enabled" ]] \
  || { echo "Versioning is not enabled on bucket raw" >&2; exit 1; }

# 3. Lifecycle, read back to make sure SeaweedFS stored every rule
admin s3api put-bucket-lifecycle-configuration --bucket raw --lifecycle-configuration file:///s3-init/lifecycle.json
rules="$(admin s3api get-bucket-lifecycle-configuration --bucket raw --query 'length(Rules)' --output text)"
[[ "$rules" == "3" ]] || { echo "Expected 3 lifecycle rules on bucket raw, found $rules" >&2; exit 1; }

# 4. The etl identity may list and read, and may write only under gtfs-static/ (C-06)
etl s3api list-objects-v2 --bucket raw --max-items 1 >/dev/null \
  || { echo "etl credentials cannot list bucket raw" >&2; exit 1; }
if printf 'probe' | etl s3 cp - s3://raw/gtfs.vehicle_positions/_probe >/dev/null 2>&1; then
  admin s3 rm s3://raw/gtfs.vehicle_positions/_probe >/dev/null
  echo "etl credentials can write outside gtfs-static/; check s3.json" >&2
  exit 1
fi

echo "Raw zone ready"
