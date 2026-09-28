# Design system

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-35
> Phụ thuộc: DOC-34, DOC-15 (enum), DOC-23 §7.3 (mức tin cậy ETA), DOC-32, ADR-0020, ADR-0021, NFR-11
> Người dùng chính: P5-03 (token và component nền), P5-06…P5-14, người viết `screens/*`

## 1. Mục đích và phạm vi

Chốt token (màu, chữ, khoảng cách, bo góc, chuyển động), danh mục component dùng chung kèm props, style bản đồ, quy ước biểu đồ và tiêu chí a11y. Mọi màn hình chỉ dùng token và component ở đây; màn hình cần thứ mới thì bổ sung vào tài liệu này trước.

Nhãn hiển thị (tiếng Anh) của từng giá trị enum nằm ở DOC-37 §3. Tài liệu này chỉ chốt **tông màu và icon** cho từng giá trị.

## 2. Nguyên tắc

- **Tailwind CSS 4 + CSS variables.** Token khai báo trong `src/styles/tokens.css` bằng `@theme` (token tĩnh) và biến CSS trên `:root` / `.dark` (token đổi theo theme). Tên biến của shadcn/ui (`--background`, `--foreground`, `--primary`…) được giữ nguyên để component sinh ra dùng được ngay.
- Màu tham chiếu **bảng màu mặc định của Tailwind 4** (`var(--color-sky-600)`…), không tự đặt mã màu. Nhờ đó token luôn đúng với bản Tailwind đang dùng.
- Component chỉ dùng **token ngữ nghĩa** (`tone-warning-bg`), không dùng trực tiếp tên màu (`amber-100`). ESLint rule `tailwindcss/no-arbitrary-value` và quy ước review chặn class màu thô trong `features/`.
- Icon: `lucide-react` (bộ icon mặc định của shadcn/ui), cỡ 16 px trong ops, 20 px ở màn hành khách, `aria-hidden="true"` khi đã có chữ đi kèm.

## 3. Màu

### 3.1 Nền và chữ

| Token | Light | Dark | Dùng cho |
| --- | --- | --- | --- |
| `--background` | `white` | `slate-950` | Nền trang |
| `--foreground` | `slate-900` | `slate-50` | Chữ chính |
| `--card` / `--popover` | `white` | `slate-900` | Thẻ, popover, drawer |
| `--muted` | `slate-100` | `slate-800` | Nền phụ, dòng được chọn, skeleton |
| `--muted-foreground` | `slate-600` | `slate-400` | Chữ phụ, nhãn trục biểu đồ |
| `--border` / `--input` | `slate-200` | `slate-800` | Viền, lưới biểu đồ |
| `--ring` | `sky-600` | `sky-400` | Focus ring |
| `--primary` / `--primary-foreground` | `sky-700` / `white` | `sky-400` / `slate-950` | Nút chính, link |
| `--destructive` / `--destructive-foreground` | `red-700` / `white` | `red-500` / `slate-950` | Nút thao tác nguy hiểm |

Cặp chữ/nền trên đều đạt tương phản ≥ 4,5:1 (kiểm bằng axe trên `/_ui`, §10).

### 3.2 Tông trạng thái

Mọi badge, pill và dải thông báo dùng một trong bảy **tông**. Mỗi tông có bốn biến: `bg` (nền nhạt), `fg` (chữ trên nền nhạt), `border`, `solid` (chấm, icon trên bản đồ, cột biểu đồ).

| Tông | Light `bg` / `fg` / `border` | Dark `bg` / `fg` / `border` | `solid` (hai theme) |
| --- | --- | --- | --- |
| `neutral` | `slate-100` / `slate-700` / `slate-300` | `slate-800` / `slate-200` / `slate-600` | `slate-500` |
| `info` | `sky-100` / `sky-800` / `sky-300` | `sky-950` / `sky-200` / `sky-800` | `sky-600` |
| `success` | `emerald-100` / `emerald-800` / `emerald-300` | `emerald-950` / `emerald-200` / `emerald-800` | `emerald-600` |
| `teal` | `teal-100` / `teal-800` / `teal-300` | `teal-950` / `teal-200` / `teal-800` | `teal-600` |
| `warning` | `amber-100` / `amber-900` / `amber-300` | `amber-950` / `amber-200` / `amber-800` | `amber-500` |
| `danger` | `red-100` / `red-800` / `red-300` | `red-950` / `red-200` / `red-800` | `red-600` |
| `progress` | `violet-100` / `violet-800` / `violet-300` | `violet-950` / `violet-200` / `violet-800` | `violet-600` |

Biến: `--tone-<tông>-bg`, `--tone-<tông>-fg`, `--tone-<tông>-border`, `--tone-<tông>-solid`; utility Tailwind tương ứng `bg-tone-warning-bg`, `text-tone-warning-fg`…

### 3.3 Ánh xạ giá trị → tông và icon

**Severity** (`alert_event.severity`, `dead_letter.severity`, ticketing):

| Giá trị | Tông | Icon (lucide) |
| --- | --- | --- |
| `0` | `info` | `Info` |
| `1` | `warning` | `TriangleAlert` |
| `2` | `danger` | `OctagonAlert` |
| không có (chưa phân loại) | `neutral` | `CircleDashed` |

**Trạng thái job** (Spring Batch và micro-batch, DOC-15 §5):

| Giá trị | Tông | Icon |
| --- | --- | --- |
| `STARTING`, `STARTED`, `STOPPING` | `progress` | `LoaderCircle` (xoay; đứng yên khi reduced motion) |
| `COMPLETED` | `success` | `CircleCheck` |
| `COMPLETED_WITH_SKIPS` | `warning` | `CircleAlert` |
| `FAILED` | `danger` | `CircleX` |
| `STOPPED`, `ABANDONED`, `UNKNOWN` | `neutral` | `CircleStop` / `CircleSlash` / `CircleHelp` |

**`job_request.status`, `replay_request.status`, `sim_scenario_run.status`:**

| Giá trị | Tông | Icon |
| --- | --- | --- |
| `PENDING` | `neutral` | `Clock` |
| `RUNNING` | `progress` | `LoaderCircle` |
| `DONE`, `COMPLETED` | `success` | `CircleCheck` |
| `FAILED`, `REJECTED` | `danger` | `CircleX` |
| `STOPPED` | `neutral` | `CircleStop` |

**`dead_letter.status`:**

| Giá trị | Tông | Icon |
| --- | --- | --- |
| `NEW` | `neutral` | `Inbox` |
| `TRIAGING`, `AUTO_REPLAY_SCHEDULED`, `REPLAY_REQUESTED` | `progress` | `LoaderCircle` |
| `TRIAGED` | `info` | `Tags` |
| `PENDING_CONFIRM` | `warning` | `CircleHelp` |
| `MANUAL` | `warning` | `Hand` |
| `REPLAYED`, `RESOLVED` | `success` | `CircleCheck` |
| `DISCARDED` | `neutral` | `Trash2` |

**Feed (`feed_version.status`):** `STAGED` → `info`; `ACTIVE` → `success`; `RETIRED` → `neutral`; `REJECTED` → `danger`.

**Mức tin cậy ETA** (DOC-23 §7.3):

| Giá trị | Tông | Biểu diễn không dùng màu |
| --- | --- | --- |
| `HIGH` | `success` | 3 vạch đầy |
| `MEDIUM` | `teal` | 2 vạch |
| `LOW` | `warning` | 1 vạch |
| `NONE` | `neutral` | 0 vạch, chữ "Schedule only" |

**Confidence của AI** (số trong `[0, 1]`): `≥ 0,8` → `success`; `0,6 … < 0,8` → `teal`; `< 0,6` → `warning` và luôn kèm chữ "Low confidence" (ngưỡng 0,6 lấy từ `lowConfidence` của API khi có, FR-09.6).

**Nguồn dữ liệu** (dùng làm màu series biểu đồ, không phải tông):

| `source` | Màu |
| --- | --- |
| `GTFS_RT_VEHICLE_POSITION` | `sky-600` |
| `GTFS_RT_TRIP_UPDATE` | `violet-600` |
| `TICKETING_SALES` | `emerald-600` |
| `TICKETING_SALE_POINTS` | `amber-600` |
| `GTFS_STATIC` | `slate-500` |

### 3.4 Độ trễ

Dùng cho icon xe trên bản đồ, cột trễ trong danh sách và ô heatmap. Ngưỡng ±300 s khớp ngưỡng OTP mặc định (`earlyToleranceSeconds`/`lateToleranceSeconds`, DOC-23 §8).

| Lớp | Điều kiện (`delaySeconds`) | Màu | Chữ |
| --- | --- | --- | --- |
| `early` | < −300 | `sky-600` | "Early" |
| `on-time` | −300 … 300 | `emerald-600` | "On time" |
| `late` | 301 … 600 | `amber-500` | "Late" |
| `very-late` | > 600 | `red-600` | "Very late" |
| `unknown` | không có | `slate-400` | "No delay data" |

### 3.5 Bảng màu biểu đồ

- **Phân loại** (series không phải nguồn), theo thứ tự: `sky-600`, `violet-600`, `emerald-600`, `amber-600`, `rose-600`, `cyan-600`, `lime-700`, `slate-500`. Dark theme dùng mức `-400` của cùng màu.
- **Tuần tự** cho heatmap độ trễ (thấp → cao): `emerald-100`, `lime-200`, `amber-200`, `amber-400`, `orange-500`, `red-600`, `red-800`. Dark theme: `emerald-900`, `lime-800`, `amber-800`, `amber-600`, `orange-500`, `red-500`, `red-300`.
- **Tuần tự** cho OTP (cao là tốt): đảo ngược bảng trên.

## 4. Chữ, khoảng cách, bo góc, chuyển động

| Token | Giá trị |
| --- | --- |
| Font chữ | `Inter Variable` (`@fontsource-variable/inter`), dự phòng `system-ui, sans-serif` |
| Font mono | `JetBrains Mono Variable` (`@fontsource-variable/jetbrains-mono`), dự phòng `ui-monospace, monospace`. Dùng cho id, payload, JSON, tên job, `runId` |
| Cỡ chữ | `text-xs` 12/16, `text-sm` 14/20 (chữ thân ops), `text-base` 16/24 (chữ thân hành khách), `text-lg` 18/28, `text-xl` 20/28, `text-2xl` 24/32 (tiêu đề trang) |
| Độ đậm | 400 thường, 500 nhãn và tiêu đề cột, 600 tiêu đề |
| Số | `tabular-nums` cho mọi cột số, đồng hồ đếm, ETA |
| Khoảng cách | Lưới 4 px (thang mặc định của Tailwind). Lề trang 16 px (mobile), 24 px (≥ 1024 px). Khoảng giữa panel 16 px |
| Bo góc | `--radius-sm` 4 px (badge, pill), `--radius-md` 6 px (nút, ô nhập), `--radius-lg` 8 px (thẻ, drawer, dialog) |
| Đổ bóng | Chỉ cho popover, dialog, drawer, bottom sheet (`shadow-lg`) |
| Chiều cao dòng bảng | 32 px (ops), 48 px (danh sách hành khách) |
| Vùng chạm | ≥ 44 × 44 px ở màn hành khách; ≥ 24 × 24 px ở ops (WCAG 2.5.8) |
| Focus | `outline` 2 px màu `--ring`, `outline-offset` 2 px, chỉ khi `:focus-visible` |
| Chuyển động | 150 ms `ease-out` cho hover/nhấn; 200 ms cho drawer, dialog; highlight dòng mới 2 s. `prefers-reduced-motion: reduce` → tắt mọi transition, bản đồ dùng `jumpTo` thay cho `flyTo`, icon `LoaderCircle` đứng yên |
| Z-index | banner 30, thanh trên 40, drawer 50, dialog 60, toast 70 |

## 5. Danh mục component

Component nằm ở `src/components/`, dùng primitive của shadcn/ui (`src/components/ui/`). Props dưới đây là hợp đồng; chi tiết cài đặt tự do. Mọi chuỗi mặc định lấy từ `src/i18n/en.ts`.

### 5.1 Hiển thị trạng thái

```ts
type Tone = 'neutral' | 'info' | 'success' | 'teal' | 'warning' | 'danger' | 'progress';

/** Severity 0..2; null = not yet classified ("Unclassified"). */
interface SeverityBadgeProps { severity: 0 | 1 | 2 | null; size?: 'sm' | 'md'; showLabel?: boolean /* default true */ }

type StatusDomain = 'job' | 'jobRequest' | 'replay' | 'dlq' | 'feed' | 'scenarioRun';
/** Maps (domain, status) to tone, icon and label via §3.3 and DOC-37 §3. Unknown status → neutral + raw value. */
interface StatusPillProps { domain: StatusDomain; status: string; size?: 'sm' | 'md' }

/** ETA confidence (level) or AI confidence (value). Exactly one of level / value. */
interface ConfidenceChipProps {
  level?: 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';
  value?: number;               // 0..1
  sampleCount?: number;         // shown in the tooltip for ETA
  lowConfidence?: boolean;      // from the API when present; otherwise value < 0.6
  modelVersion?: string;        // shown in the tooltip for AI values
}

/** Horizontal bar 0..100 % with the numeric value; used in drawers and tables. */
interface ConfidenceMeterProps { value: number; lowConfidence?: boolean; label?: string }

interface DelayBadgeProps { delaySeconds?: number | null; variant?: 'text' | 'chip' }  // §3.4
interface RouteBadgeProps { routeId: string; displayName: string; color?: string; textColor?: string; size?: 'sm' | 'md' }
```

- `RouteBadge` dùng `route_color`/`route_text_color` của GTFS. Nếu tương phản giữa hai màu < 4,5:1 hoặc thiếu màu thì tự chọn chữ đen hoặc trắng cho tương phản cao hơn; thiếu màu nền thì dùng màu phân loại theo hash của `routeId` (§3.5).
- `ConfidenceChip` luôn có tooltip giải thích (DOC-37 §5).

### 5.2 Thời gian và độ tươi

```ts
type TimeAxis = 'event' | 'audit';   // DOC-34 §8

interface TimestampProps { at: string; format?: 'time' | 'datetime' | 'date'; showZone?: boolean /* default true */ }
interface RelativeTimeProps { at: string; axis: TimeAxis }            // re-renders every 1 s under 1 min, 30 s after
interface DurationProps { ms?: number; iso?: string }                 // "4 min 12 s", DOC-37 §4

/** "Updated 5 s ago" / "As of Sep 29, 4:05 PM CDT"; turns warning when older than staleAfterSeconds. */
interface FreshnessIndicatorProps {
  asOf?: string;
  axis: TimeAxis;
  mode?: 'relative' | 'absolute';
  staleAfterSeconds?: number;
}

/** Global banner driven by FreshnessProvider (E-60 `stale`) and RealtimeProvider status. No props. */
declare function StaleBanner(): JSX.Element | null;

/** Per-source notice (e.g. ticketing stale on the ticketing screen). */
interface SourceStaleNoticeProps { source: 'GTFS_RT_VEHICLE_POSITION' | 'GTFS_RT_TRIP_UPDATE' | 'TICKETING_SALES' }

/** Dot + label in the top bar: Live / Reconnecting… / Polling. Reads RealtimeProvider. */
declare function RealtimeStatusDot(): JSX.Element;
```

Mọi `Timestamp` có `title` (tooltip gốc của trình duyệt) là thời điểm ISO UTC đầy đủ, để người vận hành copy khi cần đối chiếu log.

### 5.3 Dữ liệu dạng bảng và bộ lọc

```ts
interface DataTableProps<T> {
  columns: ColumnDef<T, unknown>[];          // TanStack Table
  data: T[];
  getRowId: (row: T) => string;
  selectedId?: string;                       // highlighted row (drawer open)
  onRowOpen?: (row: T) => void;              // click / Enter
  virtual?: boolean;                         // TanStack Virtual; required for DLQ (≥ 10,000 rows)
  hasNextPage?: boolean;
  fetchNextPage?: () => void;                // called when the last 20 rows become visible
  isFetchingNextPage?: boolean;
  isLoading?: boolean;                       // skeleton rows
  empty?: React.ReactNode;                   // EmptyState
  newRowIds?: Set<string>;                   // 2 s highlight (P-5)
  caption: string;                           // visually hidden <caption>
  density?: 'compact' | 'comfortable';
}

interface MultiSelectFilterProps<V extends string | number> {
  label: string;
  options: { value: V; label: string; count?: number }[];
  value: V[];
  onChange: (value: V[]) => void;
}

interface TimeRangeValue { window?: string; from?: string; to?: string }
interface TimeRangePickerProps {
  value: TimeRangeValue;
  presets: string[];                          // e.g. ['15m', '1h', '6h', '24h']
  maxRangeSeconds: number;                    // mirrors the API limit (DOC-32)
  granularity: 'minute' | 'date';
  onChange: (value: TimeRangeValue) => void;
}

interface RouteSelectProps {
  value: string[];
  onChange: (routeIds: string[]) => void;
  multiple?: boolean;                          // default true
  max?: number;                                // default 20 (API limit)
  routeTypes?: number[];
  placeholder?: string;
}

/** "3 new — show" pill shown above a list when realtime rows are held back (P-5). */
interface NewItemsPillProps { count: number; onShow: () => void }
```

- `DataTable` render `<table>` thật. Khi virtualize, `<tbody>` chỉ chứa dòng đang thấy, có `aria-rowcount` (tổng đã tải, cộng 1 nếu còn trang sau) và `aria-rowindex` trên từng dòng.
- Cột id dùng `IdText` (8 ký tự đầu, mono, nút copy khi hover hoặc focus).
- Tiêu đề cột có thể sắp xếp chỉ khi API hỗ trợ; bảng keyset của DOC-32 không sắp xếp phía client.
- `RouteSelect` tải E-01 một lần (cache vô hạn trong phiên, invalidate khi `activeFeed.feedVersionId` của E-60 đổi), tìm theo `shortName`, `longName`.

### 5.4 Trạng thái trang và panel

```ts
interface EmptyStateProps { title: string; description?: string; action?: { label: string; onClick: () => void } }
interface ErrorStateProps { error: unknown; variant: 'block' | 'inline'; onRetry?: () => void }
interface NoAccessStateProps { requiredRole: 'viewer' | 'operator'; signedIn: boolean }
interface PanelSkeletonProps { variant: 'table' | 'chart' | 'list' | 'detail'; rows?: number }
```

`ErrorState` đọc `ApiError` (DOC-34 §9.4) để chọn nội dung theo slug (DOC-37 §2) và hiển thị `traceId` kèm nút copy. `variant="inline"` là dải nằm trên dữ liệu cũ (P-3).

### 5.5 Thao tác

```ts
interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: React.ReactNode;              // states the consequence (P-7)
  confirmLabel: string;
  tone?: 'default' | 'danger';
  reason?: { label: string; min: number; max: number; placeholder?: string };  // discard, resolve
  onConfirm: (reason?: string) => Promise<void>;   // dialog stays open and shows the error on failure
  onOpenChange: (open: boolean) => void;
}

interface CopyButtonProps { value: string; label: string }          // label is the accessible name
interface IdTextProps { id: string; length?: number /* default 8 */; copy?: boolean }
interface KeyValueListProps { items: { label: string; value: React.ReactNode }[]; columns?: 1 | 2 }
interface StatCardProps { label: string; value: React.ReactNode; tone?: Tone; hint?: string; href?: string }
interface DetailDrawerProps { title: React.ReactNode; open: boolean; onClose: () => void; width?: 560 | 720; footer?: React.ReactNode; children: React.ReactNode }
interface RequireRoleProps { role: 'viewer' | 'operator'; fallback?: React.ReactNode; children: React.ReactNode }
```

- Toast dùng `sonner` (component toast của shadcn/ui): thành công 4 s, lỗi 8 s và có `traceId`; tối đa 3 toast cùng lúc; `role="status"` (thành công) hoặc `role="alert"` (lỗi).
- Nút thao tác đang chạy hiện spinner và bị disable; không có nút nào gửi hai request khi bấm hai lần.

### 5.6 JSON

```ts
interface JsonViewerProps { value: string | object; maxHeight?: number; wrap?: boolean }   // read-only, pretty-printed, mono
interface JsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  errors?: { pointer: string; message: string }[];   // from 422 invalid-payload `errors[]`
  readOnly?: boolean;
  height?: number;                                    // default 360
}
```

- `JsonEditor` tải CodeMirror qua `React.lazy` (DOC-34 §7). Có lint JSON cú pháp phía client; lỗi schema từ server gắn vào dòng theo JSON Pointer (tìm vị trí khóa trong văn bản; không tìm được thì hiện ở danh sách dưới trình sửa).
- Phím `Esc` trong trình sửa **không** đóng drawer (tránh mất bản sửa); có nút "Discard changes" riêng.

### 5.7 Biểu đồ

```ts
interface TimeSeriesChartProps {
  series: { name: string; color?: string; points: { t: string; v: number | null }[] }[];
  yLabel: string;
  stacked?: boolean;
  type?: 'line' | 'bar';
  height?: number;
  caption: string;                    // accessible summary, DOC-37 §5
}
interface HeatmapChartProps {
  rows: string[]; cols: string[];     // e.g. Mon..Sun × 0..23
  cells: { row: number; col: number; value: number | null; count?: number }[];
  scale: 'delay' | 'otp';             // §3.5
  valueFormatter: (v: number) => string;
  caption: string;
}
interface SparklineProps { points: number[]; label: string; tone?: Tone }   // plain SVG, no ECharts
```

Quy ước ở §7.

### 5.8 Bản đồ

Component ở `features/map/`, dùng chung `MapCanvas` (bọc `react-map-gl/maplibre`) cho Live map và bản đồ nhỏ ở Stop detail. Xem §6.

## 6. Style bản đồ

### 6.1 Nền

- Style `offline` (PMTiles) hoặc `online` theo `env.js` (ADR-0021). Style `offline` dựng lúc chạy bằng `@protomaps/basemaps` (`frontend/src/features/map/baseStyle.ts`; flavor `grayscale` cho theme sáng, `black` cho theme tối, không ghi đè màu). Đổi theme gọi `map.setStyle` với style của theme mới rồi thêm lại các lớp dữ liệu.
- Nền không màu (hai flavor trên), nên màu tuyến và màu độ trễ nổi bật. Nhãn đường và địa danh bằng tiếng Anh (`lang: "en"`).
- Camera mặc định: fit bbox `-93.730, 44.707, -92.806, 45.330` (S-05), `minZoom` 9, `maxZoom` 18, `maxBounds` = bbox nới 10%.
- Attribution luôn hiện: "© OpenStreetMap contributors · Protomaps".

### 6.2 Các lớp dữ liệu (thứ tự từ dưới lên)

| Id lớp | Nguồn | Hiển thị |
| --- | --- | --- |
| `routes-casing`, `routes-line` | GeoJSON từ E-02 của các tuyến đang chọn | Đường màu `route_color`, rộng 3 px (zoom < 12), 5 px (≥ 12); viền 1 px màu nền. Không chọn tuyến nào thì không vẽ đường tuyến |
| `disruption-stops` | `affectedStopIds` của episode đang mở trên tuyến đã chọn | Vòng tròn viền `red-600` 3 px bán kính 9 px, nhãn "!" |
| `stops` | E-06 `routeId` (khi có tuyến chọn) | Chỉ từ zoom 14: chấm trắng viền `slate-500` bán kính 4 px; trạm được chọn bán kính 7 px viền `--primary` |
| `vehicle-clusters` | GeoJSON xe, `cluster: true`, `clusterMaxZoom: 11`, `clusterRadius: 40` | Zoom < 12: vòng tròn `slate-700` (dark: `slate-300`) có số xe |
| `bunching-links` | Cặp xe của episode đang mở (viewer) | Đường đứt `fuchsia-600` 2 px nối hai xe |
| `bunching-halo` | Hai xe của mỗi cặp | Vòng `fuchsia-600` rộng 3 px bán kính 16 px; **không** bị gom cụm, luôn hiện ở mọi zoom |
| `vehicles` | GeoJSON xe (không thuộc cụm) | Icon mũi tên hình giọt nước xoay theo `bearing`, màu theo lớp trễ (§3.4), viền trắng 1,5 px. `very-late` thêm viền ngoài thứ hai. Không có `bearing` thì dùng hình tròn |
| `vehicle-selected` | Xe đang chọn | Vòng `--primary` 3 px bán kính 14 px |
| `vehicle-labels` | Xe | Zoom ≥ 14: nhãn tuyến (`displayName`) cạnh icon, chữ 11 px có halo |

- **Xe cũ:** `eventTimestamp` cũ hơn 90 s so với `businessNow` → độ mờ 0,4 và tooltip "Last seen 2 min ago". Cũ hơn 5 phút → ẩn (DOC-33 §5.1).
- **Chuyển động:** vị trí mới áp ngay (không nội suy) để khớp dữ liệu; gom mọi thay đổi trong một khung hình thành một lần `setData` (DOC-34 §7).
- **Tô nổi khi chọn cặp bunching** (`bunching=<id>` trên URL): camera fit hai xe với padding 120 px, các xe khác độ mờ 0,5.
- Lớp `vehicles` dùng `symbol` với icon SDF đăng ký lúc tải style (`map.addImage(..., { sdf: true })`) để đổi màu bằng `icon-color`, không tạo ảnh cho từng màu.

### 6.3 Chú giải

Nút "Legend" mở popover liệt kê: năm lớp trễ (icon + chữ), cặp bunching (chỉ viewer), trạm bị ảnh hưởng, xe cũ, cụm xe. Chú giải là bảng HTML, đọc được bằng screen reader.

## 7. Quy ước biểu đồ

- ECharts import theo module (`echarts/core` + `LineChart`, `BarChart`, `HeatmapChart`, `GridComponent`, `TooltipComponent`, `LegendComponent`, `VisualMapComponent`, `DataZoomComponent`, `CanvasRenderer`). Không import `echarts` đầy đủ.
- Hai theme `pti-light`, `pti-dark` đăng ký từ token: font Inter 12 px, nhãn trục `--muted-foreground`, lưới `--border`, tooltip nền `--popover` viền `--border`.
- Trục thời gian: nhãn và tooltip định dạng bằng hàm của `src/lib/time.ts` theo múi giờ agency (ECharts không hỗ trợ múi giờ tùy ý). Tooltip ghi đủ ngày, giờ và viết tắt múi giờ.
- Trục số bắt đầu từ 0 với biểu đồ cột và số đếm; biểu đồ độ trễ có đường tham chiếu tại 0 và tại ±300 s (nét đứt, ghi "On-time window").
- Giá trị thiếu (`null`) để trống, không nối đường qua chỗ thiếu (`connectNulls: false`), để thấy được khoảng consumer dừng (E-31).
- Mỗi biểu đồ có tiêu đề (heading HTML, không dùng title của ECharts), `caption` tóm tắt bằng chữ (`aria-describedby`) và nút "View as table" chuyển sang bảng số liệu tương đương.
- Không dùng biểu đồ tròn, 3D hay hiệu ứng animation khi cập nhật dữ liệu (`animation: false` khi refetch; chỉ animate lần vẽ đầu nếu không reduced motion).
- Kích thước: chiều rộng theo khung chứa (`ResizeObserver`), chiều cao cố định theo loại (timeline 240 px, heatmap 280 px, sparkline 24 px).

## 8. Tiêu chí a11y

Mục tiêu **WCAG 2.2 AA** cho màn hành khách (NFR-11) và cùng tiêu chí, ở mức nỗ lực tốt nhất, cho ops console.

| Tiêu chí | Cách đáp ứng |
| --- | --- |
| Tương phản (1.4.3, 1.4.11) | Chữ ≥ 4,5:1, thành phần UI và focus ring ≥ 3:1 trên cả hai theme; axe kiểm trên `/_ui` |
| Không chỉ dùng màu (1.4.1) | Severity, trạng thái, mức tin cậy, lớp trễ luôn có chữ hoặc icon (§3.3, §3.4); bản đồ có chế độ danh sách |
| Bàn phím (2.1.1, 2.4.7) | Mọi thao tác làm được bằng bàn phím; focus luôn thấy được; không bẫy focus trừ dialog/drawer (Radix quản lý và trả focus về nút mở) |
| Bỏ qua khối lặp (2.4.1) | Link "Skip to content" là phần tử focus đầu tiên |
| Landmark và heading | `header`, `nav` (có `aria-label` "Main" / "Ops"), `main`, một `h1` mỗi trang |
| Thông báo động (4.1.3) | Toast qua `role="status"`/`role="alert"`. Alert mới trên màn Alerts: vùng `aria-live="polite"` đọc tóm tắt "2 new alerts". Không đọc cập nhật vị trí xe |
| Bản đồ | Canvas có `aria-label` "Live vehicle map"; điều khiển zoom/pan bằng bàn phím (MapLibre `keyboard: true`); **chế độ danh sách** (`view=list`) liệt kê xe theo tuyến với cùng thông tin |
| Kích thước vùng chạm (2.5.8) | §4 |
| Phóng to (1.4.4, 1.4.10) | Không mất nội dung khi zoom 200%; màn hành khách reflow ở 320 px |
| Chuyển động (2.3.3) | Tôn trọng `prefers-reduced-motion` (§4) |
| Form (3.3.1, 3.3.2) | Mọi ô có `label`; lỗi gắn bằng `aria-describedby` và `aria-invalid`; lỗi từ server (`errors[].field`) gắn vào đúng ô |
| Bảng | `<table>` có `caption`, `th scope`; bảng virtualize có `aria-rowcount`/`aria-rowindex` |
| Ngôn ngữ | `<html lang="en">` |
| Biểu đồ | `caption` và "View as table" (§7) |

## 9. Dark mode

- Chọn trong menu người dùng (anonymous: nút trên thanh trên): "Light", "Dark", "System" (mặc định).
- Mọi token ở §3 có giá trị dark. Không component nào dùng màu cố định ngoài token.
- Bản đồ đổi sang style dark; biểu đồ đổi theme ECharts mà không mất trạng thái zoom.

## 10. Trang `/_ui`

Route chỉ có trong bản build dev (`import.meta.env.DEV`), đáp ứng P5-03. Liệt kê mọi component ở §5 với mọi biến thể (mọi severity, mọi `(domain, status)`, mọi mức tin cậy, mọi lớp trễ, `ErrorState` cho từng slug của DOC-30, bảng 10.000 dòng giả), cạnh nhau ở hai theme. Playwright chạy axe trên trang này.

## 11. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| DS-01 | Axe trên `/_ui` ở light và dark | Không có vi phạm `serious`/`critical` (gồm `color-contrast`) |
| DS-02 | Component: `StatusPill` với mọi giá trị enum của DOC-15 | Đúng tông, icon, nhãn DOC-37; giá trị lạ → `neutral` + giá trị thô |
| DS-03 | Component: `ConfidenceChip` `value=0.59` không có `lowConfidence`; `value=0.82` với `lowConfidence=true` | Lần 1 hiện "Low confidence"; lần 2 cũng hiện (ưu tiên cờ của API) |
| DS-04 | Unit: lớp trễ tại −301, −300, 300, 301, 600, 601, `null` | `early`, `on-time`, `on-time`, `late`, `late`, `very-late`, `unknown` |
| DS-05 | Unit: `RouteBadge` với `color=FFFF00`, `textColor=FFFFFF` | Chữ đổi sang đen (tương phản ≥ 4,5:1) |
| DS-06 | Component: `DataTable` virtual 10.000 dòng, `j`/`k`/`Enter` | Chỉ ≤ 60 dòng trong DOM; `aria-rowindex` đúng; `Enter` gọi `onRowOpen` |
| DS-07 | Component: `ConfirmDialog` với `reason` min 3, `onConfirm` ném `ApiError` | Nút confirm disable khi lý do < 3 ký tự; lỗi hiện trong dialog, dialog không đóng |
| DS-08 | Component: `JsonEditor` với `errors=[{pointer:'/payload/route_id'}]` | Dòng chứa khóa `route_id` có đánh dấu lỗi và thông điệp |
| DS-09 | Component: `prefers-reduced-motion` | Không có class transition; `LoaderCircle` không có animation |
| DS-10 | Component: `FreshnessIndicator` `asOf` cũ hơn `staleAfterSeconds` | Tông `warning`, chữ "Updated … ago" vẫn hiện |

## 12. Câu hỏi còn mở

Không có.
