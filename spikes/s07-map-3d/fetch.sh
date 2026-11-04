#!/bin/sh
# Spike S-07: point the spike at the real `make tiles` extract without copying it.
# The 84 MB twin-cities.pmtiles stays where the frontend serves it from; site/tiles is a gitignored symlink.
set -eu
cd "$(dirname "$0")"
TILES=${TILES:-/Users/ninggiangboy/Develops/side-project/public-transport-intelligence/deploy/tiles}
mkdir -p site
if [ ! -e site/tiles ]; then
  ln -s "$TILES" site/tiles
fi
ls -l site/tiles
