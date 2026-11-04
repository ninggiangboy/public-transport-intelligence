import { ChevronDown, ChevronUp, Compass, Minus, Plus, RotateCcw, RotateCw } from 'lucide-react';
import type { ReactNode } from 'react';

import { Switch } from '@/components/ui/switch';
// eslint-disable-next-line import-x/no-restricted-paths -- the 3D view's type-only contract, read by the map's panel (DR-111).
import type { Layers3d } from '@/features/map3d/api';
import { map3dCopy } from '@/i18n/map3d';
import { cn } from '@/lib/utils';

// The 3D map controls (screens/live-map §4): the camera cluster that replaces "Locate vehicles"/"List view", and the
// two legend switches. Loaded with the 3D view only (DR-107).

const copy = map3dCopy.map3d;
const LAYERS_KEY = 'pti.map3d.layers';
const DEFAULT_LAYERS: Layers3d = { buildings: true, lastFix: false };

/** The layer choices remembered between visits (screens/live-map §4.3); bad JSON or denied storage gives the defaults. */
// eslint-disable-next-line react-refresh/only-export-components -- the toggles export their localStorage helpers (DR-111).
export function readLayers3d(): Layers3d {
  try {
    const raw = localStorage.getItem(LAYERS_KEY);
    if (raw === null) return DEFAULT_LAYERS;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return DEFAULT_LAYERS;
    const { buildings, lastFix } = parsed as { buildings?: unknown; lastFix?: unknown };
    return {
      buildings: typeof buildings === 'boolean' ? buildings : DEFAULT_LAYERS.buildings,
      lastFix: typeof lastFix === 'boolean' ? lastFix : DEFAULT_LAYERS.lastFix,
    };
  } catch {
    return DEFAULT_LAYERS;
  }
}

// eslint-disable-next-line react-refresh/only-export-components -- the toggles export their localStorage helpers (DR-111).
export function writeLayers3d(layers: Layers3d): void {
  try {
    localStorage.setItem(LAYERS_KEY, JSON.stringify(layers));
  } catch {
    // Storage can be denied (private mode): the choice still holds for this visit.
  }
}

function ControlButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className="grid size-[34px] place-items-center border-b border-border bg-card text-foreground-2 last:border-b-0 hover:bg-muted"
    >
      {children}
    </button>
  );
}

interface CameraControlsProps {
  pitch: number;
  bearing: number;
  onTilt: (delta: number) => void;
  onRotate: (delta: number) => void;
  onResetNorth: () => void;
  onZoom: (delta: number) => void;
  className?: string;
}

/** Tilt, compass, rotation and zoom of the 3D camera (screens/live-map §4, `CameraControls`). */
export function CameraControls({
  pitch,
  bearing,
  onTilt,
  onRotate,
  onResetNorth,
  onZoom,
  className,
}: CameraControlsProps) {
  const icon = 'size-4';
  return (
    <div className={cn('absolute flex flex-col gap-2', className)}>
      <div className="flex flex-col overflow-hidden rounded-[10px] border border-border bg-card shadow-md">
        <ControlButton
          label={copy.controls.tiltUp}
          onClick={() => {
            onTilt(10);
          }}
        >
          <ChevronUp className={icon} aria-hidden="true" />
        </ControlButton>
        <div className="grid size-[34px] place-items-center border-b border-border bg-card text-[11px] font-medium text-muted-foreground tabular-nums">
          {copy.controls.pitch(Math.round(pitch))}
        </div>
        <ControlButton
          label={copy.controls.tiltDown}
          onClick={() => {
            onTilt(-10);
          }}
        >
          <ChevronDown className={icon} aria-hidden="true" />
        </ControlButton>
        <ControlButton label={copy.controls.resetNorth} onClick={onResetNorth}>
          <Compass className={icon} aria-hidden="true" style={{ transform: `rotate(${-bearing}deg)` }} />
        </ControlButton>
        <ControlButton
          label={copy.controls.rotateLeft}
          onClick={() => {
            onRotate(-20);
          }}
        >
          <RotateCcw className={icon} aria-hidden="true" />
        </ControlButton>
        <ControlButton
          label={copy.controls.rotateRight}
          onClick={() => {
            onRotate(20);
          }}
        >
          <RotateCw className={icon} aria-hidden="true" />
        </ControlButton>
      </div>
      <div className="flex flex-col overflow-hidden rounded-[10px] border border-border bg-card shadow-md">
        <ControlButton
          label={copy.controls.zoomIn}
          onClick={() => {
            onZoom(1);
          }}
        >
          <Plus className={icon} aria-hidden="true" />
        </ControlButton>
        <ControlButton
          label={copy.controls.zoomOut}
          onClick={() => {
            onZoom(-1);
          }}
        >
          <Minus className={icon} aria-hidden="true" />
        </ControlButton>
      </div>
    </div>
  );
}

interface Layers3dTogglesProps {
  layers: Layers3d;
  onChange: (next: Layers3d) => void;
}

function LayersRow({
  label,
  checked,
  onCheckedChange,
}: {
  label: string;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-[12.5px]">{label}</span>
      <Switch checked={checked} onCheckedChange={onCheckedChange} aria-label={label} />
    </div>
  );
}

/** The "Buildings" and "Last GPS fix" rows the legend adds in 3D (screens/live-map §4.3). */
export function Layers3dToggles({ layers, onChange }: Layers3dTogglesProps) {
  const set = (next: Layers3d) => {
    writeLayers3d(next);
    onChange(next);
  };
  return (
    <div className="flex flex-col gap-2 py-1">
      <LayersRow
        label={copy.layers.buildings}
        checked={layers.buildings}
        onCheckedChange={(checked) => {
          set({ ...layers, buildings: checked });
        }}
      />
      <LayersRow
        label={copy.layers.lastFix}
        checked={layers.lastFix}
        onCheckedChange={(checked) => {
          set({ ...layers, lastFix: checked });
        }}
      />
    </div>
  );
}
