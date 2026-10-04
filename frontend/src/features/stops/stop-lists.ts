import { useCallback, useState } from 'react';

import { readStored, writeStored } from '@/lib/browser';

// "Saved stops" and "Recent stops" in localStorage (DOC-36 screens/stop-detail §4). Storage may be missing or full; a
// failure only loses the list.

export interface StopRef {
  stopId: string;
  name: string;
  code?: string;
}

export const SAVED_KEY = 'pti.savedStops';
export const RECENT_KEY = 'pti.recentStops';
export const MAX_SAVED = 10;
export const MAX_RECENT = 5;

function isStopRef(value: unknown): value is StopRef {
  if (typeof value !== 'object' || value === null) return false;
  const record = value as Record<string, unknown>;
  return typeof record.stopId === 'string' && typeof record.name === 'string';
}

export function readStops(key: string): StopRef[] {
  try {
    const parsed: unknown = JSON.parse(readStored('local', key) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter(isStopRef) : [];
  } catch {
    return [];
  }
}

function writeStops(key: string, stops: StopRef[]) {
  writeStored('local', key, JSON.stringify(stops));
}

/** Whether localStorage can be written; without it "Save stop" is hidden. */
export function storageWorks(): boolean {
  try {
    const probe = 'pti.probe';
    window.localStorage.setItem(probe, '1');
    window.localStorage.removeItem(probe);
    return true;
  } catch {
    return false;
  }
}

/** Puts `stop` first, without duplicates, keeping at most `max`. */
export function withStop(stops: StopRef[], stop: StopRef, max: number): StopRef[] {
  const ref: StopRef = { stopId: stop.stopId, name: stop.name, ...(stop.code ? { code: stop.code } : {}) };
  return [ref, ...stops.filter((item) => item.stopId !== stop.stopId)].slice(0, max);
}

export function rememberRecent(stop: StopRef) {
  writeStops(RECENT_KEY, withStop(readStops(RECENT_KEY), stop, MAX_RECENT));
}

/** The saved and recent lists, with the actions that change them. */
export function useStopLists() {
  const [saved, setSaved] = useState(() => readStops(SAVED_KEY));
  const [recent, setRecent] = useState(() => readStops(RECENT_KEY));
  const toggleSaved = useCallback((stop: StopRef) => {
    setSaved((current) => {
      const next = current.some((item) => item.stopId === stop.stopId)
        ? current.filter((item) => item.stopId !== stop.stopId)
        : withStop(current, stop, MAX_SAVED);
      writeStops(SAVED_KEY, next);
      return next;
    });
  }, []);
  const clearRecent = useCallback(() => {
    setRecent([]);
    writeStops(RECENT_KEY, []);
  }, []);
  return { saved, recent, toggleSaved, clearRecent };
}
