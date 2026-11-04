// The 3D view needs WebGL 2 (DR-111 fallback). The answer never changes during a page's life, so it is computed once
// on a throwaway canvas and cached: MapViewSwitch and use-map3d both read it without touching WebGL twice.

let cached: boolean | undefined;

/** Whether the browser can render the 3D view; false when creating the context throws or is unimplemented. */
export function supportsWebGL2(): boolean {
  if (cached !== undefined) return cached;
  try {
    const canvas = document.createElement('canvas');
    cached = canvas.getContext('webgl2') !== null;
  } catch {
    cached = false;
  }
  return cached;
}
