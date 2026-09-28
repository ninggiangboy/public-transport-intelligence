#!/bin/sh
# Spike S-04: checks lifecycle, versioning and prefix-scoped write permission on SeaweedFS.
E="--endpoint-url http://seaweedfs:8333"
admin() { AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=admin-secret aws $E "$@"; }
etl() { AWS_ACCESS_KEY_ID=etl AWS_SECRET_ACCESS_KEY=etl-secret aws $E "$@"; }
echo "## versioning"; admin s3api put-bucket-versioning --bucket raw --versioning-configuration Status=Enabled && admin s3api get-bucket-versioning --bucket raw
echo "## lifecycle put"; admin s3api put-bucket-lifecycle-configuration --bucket raw --lifecycle-configuration file:///work/lifecycle.json; echo "exit=$?"
echo "## lifecycle get"; admin s3api get-bucket-lifecycle-configuration --bucket raw
echo "hello" > /tmp/x
echo "## etl write gtfs-static (expect ok)"; etl s3 cp /tmp/x s3://raw/gtfs-static/probe.txt >/dev/null; echo "exit=$?"
echo "## etl write gtfs.vehicle_positions (expect denied)"; etl s3 cp /tmp/x s3://raw/gtfs.vehicle_positions/probe.txt >/dev/null 2>&1; echo "exit=$?"
echo "## etl read + list"; etl s3 ls s3://raw/ | head -3; echo "exit=$?"
echo "## overwrite keeps noncurrent version"; admin s3 cp /tmp/x s3://raw/gtfs-static/probe.txt >/dev/null; admin s3api list-object-versions --bucket raw --prefix gtfs-static/probe.txt --query 'length(Versions)'
