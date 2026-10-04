import type { ReactNode } from 'react';

import { BunchingPanel } from '@/features/map/components/BunchingPanel';
import { DisruptionPanel } from '@/features/map/components/DisruptionPanel';
import type { PanelKind } from '@/features/map/components/panel-parts';
import { VehiclePanel } from '@/features/map/components/VehiclePanel';
import type { RouteItem } from '@/features/map/model';

// The body of the detail panel. Loaded on the first selection, not with the map (DOC-34 §7).

interface PanelContentProps {
  selection: { kind: PanelKind; id: string };
  routeIds: readonly string[];
  routes: ReadonlyMap<string, RouteItem>;
  staff: boolean;
  operator: boolean;
  following: boolean;
  onFollow: (follow: boolean) => void;
  alertId: string | undefined;
  onExpired: () => void;
}

export function PanelContent(props: PanelContentProps): ReactNode {
  const { selection } = props;
  if (selection.kind === 'vehicle')
    return (
      <VehiclePanel
        key={selection.id}
        vehicleId={selection.id}
        routeIds={props.routeIds}
        routes={props.routes}
        staff={props.staff}
        operator={props.operator}
        following={props.following}
        onFollow={props.onFollow}
      />
    );
  if (selection.kind === 'bunching')
    return (
      <BunchingPanel
        key={selection.id}
        episodeId={selection.id}
        routes={props.routes}
        operator={props.operator}
        alertId={props.alertId}
      />
    );
  return (
    <DisruptionPanel
      key={selection.id}
      episodeId={selection.id}
      routes={props.routes}
      staff={props.staff}
      alertId={props.alertId}
      onExpired={props.onExpired}
    />
  );
}
