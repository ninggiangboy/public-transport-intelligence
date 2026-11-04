// Copy of the 3D live map (DOC-36 screens/live-map §8, row "Chế độ 3D"). This file is imported only by the lazily
// loaded 3D pieces, so it ships in the lazy chunk and never in /map (DR-107).

export const map3dCopy = {
  map3d: {
    canvasLabel: '3D vehicle map',
    view: {
      label: 'Map view',
      two: '2D',
      three: '3D',
      webgl: '3D needs a browser with WebGL 2.',
    },
    loading: 'Loading 3D…',
    failed: "Couldn't load the 3D view.",
    retry: 'Retry',
    controls: {
      zoomIn: 'Zoom in',
      zoomOut: 'Zoom out',
      tiltUp: 'Tilt up',
      tiltDown: 'Tilt down',
      resetNorth: 'Reset bearing to north',
      rotateLeft: 'Rotate left',
      rotateRight: 'Rotate right',
      pitch: (degrees: number) => `${degrees}°`,
    },
    camera: {
      title: 'Camera',
      free: 'Free',
      follow: 'Follow',
      chase: 'Chase',
      freeHint: 'Drag to pan. Right-drag or Ctrl + drag to rotate and tilt.',
      followHint: (vehicle: string) =>
        `Keeps ${vehicle} in the centre as it moves. Dragging the map switches back to Free.`,
      chaseHint: (vehicle: string) => `Rides behind ${vehicle} with the direction of travel pointing up.`,
    },
    layers: {
      buildings: 'Buildings',
      lastFix: 'Last GPS fix',
    },
    estimate: (seconds: number) => `Position estimated between GPS fixes · last fix ${seconds} s ago`,
    slow: {
      message: '3D was running slowly, so buildings are off.',
      turnBackOn: 'Turn back on',
    },
    /** The label disc and the selected-vehicle card name the vehicle "Bus {label}" or "Train {label}". */
    kind: (train: boolean) => (train ? 'Train' : 'Bus'),
    /** The sentences of the camera hints say "bus {label}" or "train {label}". */
    vehicle: (train: boolean, label: string) => `${train ? 'train' : 'bus'} ${label}`,
    disc: (kind: string, label: string, route: string, delay: string) => `${kind} ${label}, route ${route}, ${delay}`,
    card: (kind: string, label: string) => `${kind} ${label}`,
    cardDetail: (delay: string, speed: string) => `${delay} · ${speed}`,
  },
} as const;
