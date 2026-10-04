import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';

import { routeName, type RouteItem } from '@/features/map/model';
import { mapAlertsQuery } from '@/features/map/queries';
import { mapCopy } from '@/i18n/map';
import { notify } from '@/lib/notify';
import { toMillis } from '@/lib/time';

// Toasts for new disruption and bunching alerts on the routes shown (screens/live-map §6, UC-03). Components never
// read the stream (DOC-26 §8.4): `alert.created` lands in this alert list like in any other, and a new id in it is
// the toast. Alerts already open when the page loaded raise none, nor do old ones that a new route filter brings in.

const copy = mapCopy.map.toast;

/** An alert older than this when it first shows up is not news. */
const FRESH_MS = 5 * 60_000;

interface MapToastOptions {
  routeIds: readonly string[];
  /** Viewer and above also hear about bunching. */
  staff: boolean;
  routes: ReadonlyMap<string, RouteItem>;
  onShowDisruption: (id: string, routeId: string | undefined) => void;
  onShowBunching: (id: string, routeId: string | undefined) => void;
}

export function useMapToasts({ routeIds, staff, routes, onShowDisruption, onShowBunching }: MapToastOptions) {
  const query = useQuery(mapAlertsQuery(routeIds, staff ? ['DISRUPTION', 'BUNCHING'] : ['DISRUPTION']));
  const seen = useRef<Set<string>>(undefined);
  const latest = useRef({ routes, onShowDisruption, onShowBunching });
  useEffect(() => {
    latest.current = { routes, onShowDisruption, onShowBunching };
  });

  const items = query.data?.data.items;
  useEffect(() => {
    if (!items) return;
    if (!seen.current) {
      seen.current = new Set(items.map((alert) => alert.id));
      return;
    }
    for (const alert of items) {
      if (seen.current.has(alert.id)) continue;
      seen.current.add(alert.id);
      // `createdAt` is audit time: the machine clock (DOC-34 §8).
      if (Date.now() - toMillis(alert.createdAt) > FRESH_MS || !alert.refId) continue;
      const refId = alert.refId;
      const { routes: names, onShowDisruption: showDisruption, onShowBunching: showBunching } = latest.current;
      const route = alert.routeId ? routeName(alert.routeId, names) : '';
      if (alert.type === 'DISRUPTION') {
        const delay = alert.body.currentAvgDelaySeconds;
        const minutes = typeof delay === 'number' ? Math.max(1, Math.round(delay / 60)) : 0;
        notify.warning(copy.disruption(route, minutes), {
          action: {
            label: copy.show,
            onClick: () => {
              showDisruption(refId, alert.routeId);
            },
          },
        });
      } else if (alert.type === 'BUNCHING') {
        notify.message(copy.bunching(route), {
          action: {
            label: copy.showOnMap,
            onClick: () => {
              showBunching(refId, alert.routeId);
            },
          },
        });
      }
    }
  }, [items]);

  return query.data?.data.items;
}
