import { Pin } from 'lucide-react';

import { SegmentedControl } from '@/components/SegmentedControl';
import { PanelSection } from '@/features/map/components/panel-parts';
// eslint-disable-next-line import-x/no-restricted-paths -- the 3D view's type-only contract, read by the map's panel (DR-111).
import type { CameraMode } from '@/features/map3d/api';
import { map3dCopy } from '@/i18n/map3d';

// The "Camera" block of the vehicle panel and the "Position estimated…" row, both only in 3D (screens/live-map §4.3).

const copy = map3dCopy.map3d;
const CAMERA_MODES: readonly CameraMode[] = ['free', 'follow', 'chase'];

interface CameraBlockProps {
  mode: CameraMode;
  onMode: (mode: CameraMode) => void;
  /** "bus 21" or "train 21", built by the panel from the vehicle's label and route type. */
  vehicle: string;
}

/** Free / Follow / Chase and the line under it describing the mode on show (screens/live-map §4.3, §8). */
export function CameraBlock({ mode, onMode, vehicle }: CameraBlockProps) {
  return (
    <PanelSection title={copy.camera.title}>
      <SegmentedControl
        size="sm"
        label={copy.camera.title}
        value={mode}
        onChange={onMode}
        options={CAMERA_MODES.map((value) => ({ value, label: copy.camera[value] }))}
      />
      <p className="mt-2 text-[12.5px] text-muted-foreground">{hint(mode, vehicle)}</p>
    </PanelSection>
  );
}

function hint(mode: CameraMode, vehicle: string): string {
  if (mode === 'follow') return copy.camera.followHint(vehicle);
  if (mode === 'chase') return copy.camera.chaseHint(vehicle);
  return copy.camera.freeHint;
}

/** "Position estimated between GPS fixes · last fix {n} s ago", under the three numbers (screens/live-map §4.3). */
export function EstimateRow({ fixAgeSeconds }: { fixAgeSeconds: number }) {
  const seconds = Math.max(0, Math.round(fixAgeSeconds));
  return (
    <p
      role="status"
      aria-live="off"
      className="flex items-center gap-1.5 px-[18px] pb-4 text-[12.5px] text-muted-foreground"
    >
      <Pin className="size-3.5 shrink-0" aria-hidden="true" />
      {copy.estimate(seconds)}
    </p>
  );
}
