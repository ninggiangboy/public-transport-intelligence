// (b) deck.gl over MapLibre with MapboxOverlay: one SimpleMeshLayer of 600 instanced boxes.
// Positions come from the same shared motion module, so only the drawing differs from (a).

import { MapboxOverlay } from '@deck.gl/mapbox';
import { COORDINATE_SYSTEM } from '@deck.gl/core';
import { SimpleMeshLayer } from '@deck.gl/mesh-layers';
import { CubeGeometry } from '@luma.gl/engine';

import { advance, alphaOf, applyFix, createVehicleState, positionOf } from './motion.js';

// CubeGeometry spans [-1, 1] (half-extent 1), so a scale of size/2 gives a box of `size` metres.
const BUS = { width: 2.6, length: 12, height: 3.4 };

export function start({ map, vehicles, motion, now: nowFn, interleaved }) {
  const mesh = new CubeGeometry();
  const states = vehicles.map((v) =>
    createVehicleState({ shape: v.shape, along: v.along, speedMps: v.speedMps, tripId: v.tripId }));
  const nextFix = new Array(vehicles.length).fill(0);

  const data = vehicles.map((v) => ({
    position: [0, 0, 0],
    color: [v.color[0], v.color[1], v.color[2], 255],
    orientation: [0, 0, 0],
    scale: [BUS.width / 2, BUS.length / 2, BUS.height / 2],
  }));

  const overlay = new MapboxOverlay({ interleaved: Boolean(interleaved), layers: [] });
  map.addControl(overlay);

  // deck.gl layers are immutable: a new data identity (data.slice()) makes deck re-run the
  // accessors each frame, while the shared CubeGeometry is not re-created.
  function buildLayer() {
    return new SimpleMeshLayer({
      id: 's07-vehicles-deck',
      data: data.slice(),
      mesh,
      coordinateSystem: COORDINATE_SYSTEM.LNGLAT,
      _instanced: true,
      pickable: false,
      getPosition: (d) => d.position,
      getColor: (d) => d.color,
      getOrientation: (d) => d.orientation,
      getScale: (d) => d.scale,
    });
  }
  overlay.setProps({ layers: [buildLayer()] });

  let last = nowFn();
  let raf = 0;

  function frame() {
    const t = nowFn();
    const dt = Math.min(0.1, t - last);
    last = t;
    for (let i = 0; i < vehicles.length; i++) {
      const v = vehicles[i];
      const s = states[i];
      while (nextFix[i] < v.fixes.length && v.fixes[nextFix[i]].t <= t) {
        applyFix(s, v.fixes[nextFix[i]], t);
        nextFix[i]++;
      }
      advance(s, t, dt);
      const [lon, lat] = positionOf(s);
      const d = data[i];
      d.position[0] = lon;
      d.position[1] = lat;
      d.position[2] = BUS.height / 2; // lift the box base to the ground
      d.orientation[1] = -s.heading; // deck yaw, degrees
      d.color[3] = Math.round(255 * alphaOf(s, t));
    }
    // A new data identity each frame makes deck re-run the accessors; the mesh stays shared.
    overlay.setProps({ layers: [buildLayer()] });
    map.triggerRepaint();
    raf = requestAnimationFrame(frame);
  }
  raf = requestAnimationFrame(frame);

  return {
    stop() {
      cancelAnimationFrame(raf);
      map.removeControl(overlay);
    },
    positions: () => states.map((s) => positionOf(s)),
  };
}
