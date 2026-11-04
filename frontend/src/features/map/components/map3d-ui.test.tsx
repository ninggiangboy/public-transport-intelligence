import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getRoute, listRoutes } from '@/api/generated/examples';
import type { ResponseBody } from '@/api/types';
import { createQueryClient } from '@/app/query-client';
import { CameraBlock, EstimateRow } from '@/features/map/components/CameraBlock';
import { CameraControls, Layers3dToggles, readLayers3d, writeLayers3d } from '@/features/map/components/Map3dControls';
import { VehiclePanel } from '@/features/map/components/VehiclePanel';
import { mapCopy } from '@/i18n/map';
import { map3dCopy } from '@/i18n/map3d';
import { BusinessClockProvider } from '@/lib/business-clock';
import { respond } from '@/test/handlers';
import { server } from '@/test/server';

// The lazily loaded 3D pieces of the live map (screens/live-map §4, §4.3, §8): the camera controls, the layer
// switches, the "Camera" block, the estimated-position row and their hook-up in the vehicle panel.

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: ReactNode }) => <a href="#">{children}</a>,
}));

const copy = map3dCopy.map3d;
const vehicleCopy = mapCopy.map.vehicle;
const LAYERS_KEY = 'pti.map3d.layers';

type Vehicle = ResponseBody<'/api/v1/vehicles/live', 'get', 200>['items'][number];

const vehicle: Vehicle = {
  vehicleId: '1203',
  label: '1203',
  routeId: '18',
  tripId: 't-2043',
  directionId: 0,
  lat: 44.948,
  lon: -93.278,
  bearing: 358,
  speedMps: 7.4,
  currentStatus: 'IN_TRANSIT_TO',
  currentStopSequence: 15,
  stopId: '51420',
  delaySeconds: 95,
  headsign: 'Downtown Minneapolis',
  occupancyStatus: 'MANY_SEATS_AVAILABLE',
  eventTimestamp: new Date().toISOString(),
  stopArrivalAt: new Date(Date.now() + 180_000).toISOString(),
};

const routes = new Map(listRoutes.examples.routes.items.map((route) => [route.routeId, route]));

beforeEach(() => {
  localStorage.clear();
  server.use(
    respond('get', '/api/v1/vehicles/live', {
      businessNow: new Date().toISOString(),
      count: 1,
      items: [vehicle],
    }),
    respond('get', '/api/v1/routes/{routeId}', getRoute.examples.route),
    respond('get', '/api/v1/stops', { items: [] }),
  );
});

describe('CameraControls', () => {
  function setup() {
    const onTilt = vi.fn();
    const onRotate = vi.fn();
    const onResetNorth = vi.fn();
    const onZoom = vi.fn();
    render(
      <CameraControls
        pitch={58}
        bearing={-30}
        onTilt={onTilt}
        onRotate={onRotate}
        onResetNorth={onResetNorth}
        onZoom={onZoom}
      />,
    );
    return { onTilt, onRotate, onResetNorth, onZoom };
  }

  it('tilts in 10° steps and shows the current pitch', async () => {
    const user = userEvent.setup();
    const handlers = setup();
    expect(screen.getByText(copy.controls.pitch(58))).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: copy.controls.tiltUp }));
    expect(handlers.onTilt).toHaveBeenLastCalledWith(10);

    await user.click(screen.getByRole('button', { name: copy.controls.tiltDown }));
    expect(handlers.onTilt).toHaveBeenLastCalledWith(-10);
  });

  it('rotates in 20° steps, resets north and zooms', async () => {
    const user = userEvent.setup();
    const handlers = setup();

    await user.click(screen.getByRole('button', { name: copy.controls.rotateLeft }));
    expect(handlers.onRotate).toHaveBeenLastCalledWith(-20);

    await user.click(screen.getByRole('button', { name: copy.controls.rotateRight }));
    expect(handlers.onRotate).toHaveBeenLastCalledWith(20);

    await user.click(screen.getByRole('button', { name: copy.controls.resetNorth }));
    expect(handlers.onResetNorth).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: copy.controls.zoomIn }));
    expect(handlers.onZoom).toHaveBeenLastCalledWith(1);

    await user.click(screen.getByRole('button', { name: copy.controls.zoomOut }));
    expect(handlers.onZoom).toHaveBeenLastCalledWith(-1);
  });

  it('points the compass against the bearing', () => {
    setup();
    const compass = screen.getByRole('button', { name: copy.controls.resetNorth }).querySelector('svg');
    expect(compass).toHaveStyle({ transform: 'rotate(30deg)' });
  });
});

describe('Layers3dToggles', () => {
  it('toggles a layer, tells the caller and persists the pair', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Layers3dToggles layers={{ buildings: true, lastFix: false }} onChange={onChange} />);

    await user.click(screen.getByRole('switch', { name: copy.layers.buildings }));
    expect(onChange).toHaveBeenCalledWith({ buildings: false, lastFix: false });
    expect(JSON.parse(localStorage.getItem(LAYERS_KEY) ?? '')).toEqual({ buildings: false, lastFix: false });

    await user.click(screen.getByRole('switch', { name: copy.layers.lastFix }));
    expect(onChange).toHaveBeenLastCalledWith({ buildings: true, lastFix: true });
  });

  it('reads the saved pair', () => {
    localStorage.setItem(LAYERS_KEY, JSON.stringify({ buildings: false, lastFix: true }));
    expect(readLayers3d()).toEqual({ buildings: false, lastFix: true });
  });

  it('falls back to the defaults when nothing is stored or the JSON is bad', () => {
    expect(readLayers3d()).toEqual({ buildings: true, lastFix: false });

    localStorage.setItem(LAYERS_KEY, '{not json');
    expect(readLayers3d()).toEqual({ buildings: true, lastFix: false });

    localStorage.setItem(LAYERS_KEY, JSON.stringify({ buildings: 'yes' }));
    expect(readLayers3d()).toEqual({ buildings: true, lastFix: false });
  });

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(readLayers3d()).toEqual({ buildings: true, lastFix: false });

    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(() => {
      writeLayers3d({ buildings: false, lastFix: true });
    }).not.toThrow();
  });
});

describe('CameraBlock', () => {
  it('shows the mode hint and reports a new mode', async () => {
    const user = userEvent.setup();
    const onMode = vi.fn();
    const { rerender } = render(<CameraBlock mode="free" onMode={onMode} vehicle="bus 1203" />);
    expect(screen.getByRole('heading', { name: copy.camera.title })).toBeInTheDocument();
    expect(screen.getByText(copy.camera.freeHint)).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: copy.camera.follow }));
    expect(onMode).toHaveBeenCalledWith('follow');

    rerender(<CameraBlock mode="follow" onMode={onMode} vehicle="bus 1203" />);
    expect(screen.getByText(copy.camera.followHint('bus 1203'))).toBeInTheDocument();

    rerender(<CameraBlock mode="chase" onMode={onMode} vehicle="train 21" />);
    expect(screen.getByText(copy.camera.chaseHint('train 21'))).toBeInTheDocument();
  });
});

describe('EstimateRow', () => {
  it('rounds the fix age, floors it at zero and stays out of live regions', () => {
    const { rerender } = render(<EstimateRow fixAgeSeconds={6.4} />);
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent(copy.estimate(6));
    expect(status).toHaveAttribute('aria-live', 'off');

    rerender(<EstimateRow fixAgeSeconds={-2} />);
    expect(screen.getByRole('status')).toHaveTextContent(copy.estimate(0));
  });
});

describe('VehiclePanel in 3D', () => {
  function renderPanel(ui: ReactNode) {
    const queryClient = createQueryClient();
    queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retry: false } });
    render(
      <QueryClientProvider client={queryClient}>
        <BusinessClockProvider timezone="America/Chicago">{ui}</BusinessClockProvider>
      </QueryClientProvider>,
    );
  }

  const props = {
    vehicleId: '1203',
    routeIds: [],
    routes,
    staff: false,
    operator: false,
    following: false,
    onFollow: vi.fn(),
  };

  it('replaces Follow with the Camera block and adds the estimate row when view3d is set', async () => {
    renderPanel(<VehiclePanel {...props} view3d={{ mode: 'follow', onMode: vi.fn(), fixAgeSeconds: 6 }} />);
    expect(await screen.findByRole('heading', { name: copy.camera.title })).toBeInTheDocument();
    // Route 18 is a bus (routeType 3), so the hint names "bus 1203".
    expect(screen.getByText(copy.camera.followHint('bus 1203'))).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(copy.estimate(6));
    expect(screen.queryByRole('button', { name: vehicleCopy.follow })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: vehicleCopy.stopDetails })).toBeInTheDocument();
  });

  it('keeps the Follow button without view3d', async () => {
    renderPanel(<VehiclePanel {...props} />);
    expect(await screen.findByRole('button', { name: vehicleCopy.follow })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: copy.camera.title })).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('hides the estimate row while the fix age is unknown', async () => {
    renderPanel(<VehiclePanel {...props} view3d={{ mode: 'free', onMode: vi.fn(), fixAgeSeconds: undefined }} />);
    expect(await screen.findByRole('heading', { name: copy.camera.title })).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
