import type { ReactNode } from 'react';

// Small pictures of the scenario cards (DOC-36 screens/demo-control §4), in the LineStrip motif: a route line with its
// stops and buses coloured by delay class. Decorative (`aria-hidden`); colours are tokens, so both themes work.

const LINE = 'var(--primary)';
const BUS_STROKE = 'var(--card)';

function Rail({ color = LINE }: { color?: string }) {
  return (
    <>
      <line x1="10" y1="44" x2="190" y2="44" stroke={color} strokeWidth="5" strokeLinecap="round" />
      <g fill="var(--card)" stroke={color} strokeWidth="2">
        {[24, 76, 128, 176].map((x) => (
          <circle key={x} cx={x} cy="44" r="3.5" />
        ))}
      </g>
    </>
  );
}

function Bus({ x, fill }: { x: number; fill: string }) {
  return <circle cx={x} cy="44" r="9" fill={fill} stroke={BUS_STROKE} strokeWidth="2.5" />;
}

const ART: Record<string, ReactNode> = {
  bunching: (
    <>
      <Rail />
      <Bus x={70} fill="var(--delay-late)" />
      <Bus x={92} fill="var(--delay-late)" />
      <Bus x={168} fill="var(--delay-on-time)" />
      <ellipse
        cx="81"
        cy="44"
        rx="26"
        ry="17"
        fill="none"
        stroke="var(--bunching)"
        strokeWidth="1.75"
        strokeDasharray="4 3"
      />
    </>
  ),
  disruption: (
    <>
      <path
        d="M60 44 H140"
        stroke="var(--tone-danger-solid)"
        strokeOpacity="0.18"
        strokeWidth="20"
        strokeLinecap="round"
      />
      <Rail />
      <Bus x={30} fill="var(--delay-on-time)" />
      <Bus x={84} fill="var(--delay-very-late)" />
      <Bus x={122} fill="var(--delay-very-late)" />
    </>
  ),
  'bad-data': (
    <>
      <g fill="var(--card)" stroke="var(--border-strong)" strokeWidth="1.5">
        <rect x="40" y="16" width="36" height="46" rx="5" />
        <rect x="124" y="16" width="36" height="46" rx="5" />
      </g>
      <g stroke="var(--muted-2)" strokeWidth="3" strokeLinecap="round">
        <path d="M48 28h20M48 36h14M132 28h20M132 36h14" />
      </g>
      <rect
        x="82"
        y="16"
        width="36"
        height="46"
        rx="5"
        fill="var(--tone-danger-bg)"
        stroke="var(--tone-danger-solid)"
        strokeWidth="1.5"
      />
      <path d="M92 31l16 16M108 31 92 47" stroke="var(--tone-danger-solid)" strokeWidth="3" strokeLinecap="round" />
    </>
  ),
  duplicates: (
    <>
      <g fill="var(--card)" stroke="var(--border-strong)" strokeWidth="1.5">
        <rect x="56" y="18" width="36" height="46" rx="5" />
      </g>
      <rect
        x="108"
        y="18"
        width="36"
        height="46"
        rx="5"
        fill="var(--tone-info-bg)"
        stroke="var(--tone-info-solid)"
        strokeWidth="1.5"
      />
      <rect
        x="100"
        y="12"
        width="36"
        height="46"
        rx="5"
        fill="var(--card)"
        stroke="var(--tone-info-solid)"
        strokeWidth="1.5"
      />
      <g stroke="var(--muted-2)" strokeWidth="3" strokeLinecap="round">
        <path d="M64 30h20M64 38h14M108 24h20M108 32h14" />
      </g>
    </>
  ),
  'ticket-spike': (
    <>
      <g fill="var(--muted-2)">
        {[
          [20, 50, 16],
          [40, 46, 20],
          [60, 52, 14],
          [80, 48, 18],
          [100, 44, 22],
          [120, 50, 16],
        ].map(([x = 0, y = 0, h = 0]) => (
          <rect key={x} x={x} y={y} width="14" height={h} rx="2" />
        ))}
      </g>
      <rect x="140" y="12" width="14" height="54" rx="2" fill="var(--tone-info-solid)" />
      <rect x="160" y="30" width="14" height="36" rx="2" fill="var(--tone-info-border)" />
    </>
  ),
  'refund-burst': (
    <>
      <g fill="var(--muted-2)">
        {[20, 40, 60, 80, 100].map((x) => (
          <rect key={x} x={x} y="46" width="14" height="20" rx="2" />
        ))}
      </g>
      <rect x="120" y="22" width="14" height="44" rx="2" fill="var(--tone-warning-solid)" />
      <rect x="140" y="16" width="14" height="50" rx="2" fill="var(--tone-warning-solid)" />
      <path
        d="M170 20v26m0 0-7-7m7 7 7-7"
        stroke="var(--tone-warning-solid)"
        strokeWidth="2.5"
        fill="none"
        strokeLinecap="round"
      />
    </>
  ),
  'load-ramp': (
    <>
      <path
        d="M16 64h30V52h30V40h30V26h30V14h38"
        fill="none"
        stroke="var(--tone-progress-solid)"
        strokeWidth="3"
        strokeLinejoin="round"
      />
      <path d="M16 66h168" stroke="var(--border-strong)" strokeWidth="1.5" />
    </>
  ),
  'late-delivery': (
    <>
      <Rail />
      <Bus x={60} fill="var(--delay-on-time)" />
      <circle cx="140" cy="44" r="9" fill="none" stroke="var(--delay-unknown)" strokeWidth="2" strokeDasharray="3 3" />
      <path
        d="M128 22a14 14 0 1 1 0 0.1M128 15v7l5 3"
        stroke="var(--tone-warning-solid)"
        strokeWidth="2"
        fill="none"
        strokeLinecap="round"
      />
    </>
  ),
};

const GENERIC = (
  <>
    <Rail color="var(--muted-2)" />
    <Bus x={100} fill="var(--delay-unknown)" />
  </>
);

/** The picture of a scenario by name; a scenario this UI does not know gets the plain route line. */
export function ScenarioArt({ name }: { name: string }) {
  return (
    <svg viewBox="0 0 200 80" width="200" height="80" aria-hidden="true" className="max-w-full">
      {ART[name] ?? GENERIC}
    </svg>
  );
}
