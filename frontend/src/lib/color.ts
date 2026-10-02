// Contrast helpers for the colours that arrive as data: GTFS route_color / route_text_color (DOC-35 §5.1, DS-05).

export interface Rgb {
  r: number;
  g: number;
  b: number;
}

const HEX = /^#?([0-9a-f]{6}|[0-9a-f]{3})$/i;

/** Parses a hex colour of 6 or 3 characters with or without a leading hash (GTFS has none); null otherwise. */
export function parseHexColor(value: string | null | undefined): Rgb | null {
  const match = value ? HEX.exec(value.trim()) : null;
  const digits = match?.[1];
  if (!digits) return null;
  const full = digits.length === 3 ? digits.replace(/./g, (d) => d + d) : digits;
  return {
    r: Number.parseInt(full.slice(0, 2), 16),
    g: Number.parseInt(full.slice(2, 4), 16),
    b: Number.parseInt(full.slice(4, 6), 16),
  };
}

/** CSS `rgb()` for a colour, so no hex literal leaves this file. */
export function toCssRgb({ r, g, b }: Rgb): string {
  return `rgb(${r} ${g} ${b})`;
}

function channel(value: number): number {
  const s = value / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

/** Relative luminance of WCAG 2.2 §1.4.3. */
export function luminance({ r, g, b }: Rgb): number {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** Contrast ratio between two colours, 1 to 21. */
export function contrastRatio(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

export const BLACK: Rgb = { r: 0, g: 0, b: 0 };
export const WHITE: Rgb = { r: 255, g: 255, b: 255 };

/** Black or white, whichever reads better on `background`. */
export function readableOn(background: Rgb): Rgb {
  return contrastRatio(background, BLACK) >= contrastRatio(background, WHITE) ? BLACK : WHITE;
}

/** WCAG AA for normal text. */
export const MIN_TEXT_CONTRAST = 4.5;

/**
 * The text colour of a route shield: the feed's own when it reads at 4.5:1 or better on the background, else black
 * or white, whichever contrasts more (DS-05).
 */
export function pickRouteTextColor(background: Rgb, textColor: string | undefined): Rgb {
  const wanted = parseHexColor(textColor);
  if (wanted && contrastRatio(background, wanted) >= MIN_TEXT_CONTRAST) return wanted;
  return readableOn(background);
}
