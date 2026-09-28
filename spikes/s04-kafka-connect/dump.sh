#!/bin/sh
# Runs inside the aws-cli container: downloads every object under raw/ into /out.
aws --endpoint-url http://seaweedfs:8333 s3 sync s3://raw/ /out/ >/dev/null
