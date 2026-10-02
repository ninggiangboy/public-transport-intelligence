# Design system

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-35
> Phụ thuộc: DOC-34, DOC-15 (enum), DOC-23 §7.3 (mức tin cậy ETA), DOC-32, ADR-0020, ADR-0021, NFR-11, DR-88
> Người dùng chính: P5-03 (token và component nền), P5-06…P5-14, người viết `screens/*`

## 1. Mục đích và phạm vi

Chốt token (màu, chữ, khoảng cách, bo góc, đổ bóng, chuyển động), danh mục component dùng chung kèm props, style bản đồ, quy ước biểu đồ và tiêu chí a11y. Mọi màn hình chỉ dùng token và component ở đây; màn hình cần thứ mới thì bổ sung vào tài liệu này trước.

Hình ảnh tham chiếu là prototype Claude Design "PTI Screen Designs" và artifact "PTI Design System" (hướng thị giác **Wayfinding**, DR-88). Khi prototype và tài liệu khác nhau, tài liệu này thắng; prototype chỉ minh họa bố cục và cảm giác thị giác, số liệu trong đó là dữ liệu mẫu.

Nhãn hiển thị (tiếng Anh) của từng giá trị enum nằm ở DOC-37 §3. Tài liệu này chỉ chốt **tông màu và icon** cho từng giá trị.

## 2. Nguyên tắc

### 2.1 Hướng thị giác "Wayfinding"

Ngôn ngữ hình ảnh mượn từ biển báo giao thông công cộng thay vì dashboard chung chung. Khung giao diện giữ yên lặng: nền canvas xám rất nhạt, bề mặt trắng nổi lên bằng viền mảnh và bóng mềm, **một** màu nhấn indigo. Bản sắc nằm ở ba motif:

| Motif | Mô tả | Dùng ở |
| --- | --- | --- |
| **Route shield** | Ô vuông bo góc màu của tuyến (`route_color`), số tuyến đậm (`RouteBadge`, §5.1) | Mọi chỗ nhắc tới một tuyến: bảng, danh sách, panel, chip lọc |
| **Line-and-stop strip** | Đường tuyến dày 4 px, trạm là chấm trắng viền màu tuyến; đoạn đã qua màu `muted-2` (`LineStrip`, §5.9) | Tiến trình chuyến, đoạn bị gián đoạn, khoảng cách bunching, sơ đồ mạng lưới ở Overview |
| **Số lớn kiểu bảng giờ tàu** | ETA, KPI, headway đặt cỡ lớn, tracking âm, `tabular-nums` | `KpiCard`, bảng giờ đến ở Stop detail, demo console |

Hai quy tắc không đổi: mọi con số live ghi rõ độ tươi (DOC-34 P-1), mọi đầu ra AI ghi rõ độ tin cậy (P-2).

### 2.2 Cài đặt

- **Tailwind CSS 4 + CSS variables.** Token khai báo trong `src/styles/tokens.css` bằng `@theme` (token tĩnh: bo góc, font, bóng) và biến CSS trên `:root` / `.dark` (token đổi theo theme). Tên biến của shadcn/ui (`--background`, `--foreground`, `--primary`…) được giữ nguyên để component sinh ra dùng được ngay; token bổ sung (`--surface`, `--muted-2`, `--subtle-foreground`, `--primary-soft`…) đặt cùng chỗ.
- Màu là **mã hex cố định** ở bảng §3 (không tham chiếu bảng màu Tailwind nữa, DR-88). `tokens.css` là nơi duy nhất chứa mã hex; ngoại lệ duy nhất là màu tuyến GTFS vì đó là dữ liệu.
- Component chỉ dùng **token ngữ nghĩa** (`tone-warning-bg`), không dùng mã màu hay tên màu Tailwind (`amber-100`). ESLint rule `tailwindcss/no-arbitrary-value` và quy ước review chặn class màu thô trong `features/`.
- Icon: `lucide-react` (bộ icon mặc định của shadcn/ui), nét 1,75 px; cỡ 16 px trong trang desktop, 14 px trong badge/chip, 20–22 px ở mobile; `aria-hidden="true"` khi đã có chữ đi kèm. Nút chỉ có icon luôn có `aria-label`.

## 3. Màu

### 3.1 Nền, chữ, nhấn

| Token | Light | Dark | Dùng cho |
| --- | --- | --- | --- |
| `--background` | `#F4F5F7` | `#0D0E11` | Canvas của trang |
| `--surface` | `#FAFAFB` | `#111216` | Sidebar, dải tiêu đề bảng, chân thẻ, chân drawer/dialog |
| `--card` | `#FFFFFF` | `#16171C` | Mọi bề mặt nổi: thẻ, panel, drawer, dialog |
| `--popover` | `#FFFFFF` | `#1A1B21` | Popover, menu, tooltip |
| `--muted` | `#F0F1F4` | `#1D1F25` | Nền hover, nền segmented, skeleton |
| `--muted-2` | `#E6E8EC` | `#262830` | Rãnh progress, switch tắt, đoạn tuyến đã qua |
| `--foreground` | `#17181C` | `#ECEDF0` | Chữ chính |
| `--foreground-2` | `#3A3D45` | `#C8CAD1` | Chữ mục nav, nhãn trường |
| `--muted-foreground` | `#676B75` | `#9296A0` | Chữ phụ, nhãn trục biểu đồ (≥ 4,5:1 trên `--card`) |
| `--subtle-foreground` | `#8E929B` | `#6E727C` | Chỉ cho placeholder và nhãn nhóm nav (không mang thông tin bắt buộc) |
| `--border` | `#E4E5E9` | `#25272E` | Viền thẻ, đường kẻ bảng, lưới biểu đồ |
| `--border-strong` | `#D4D6DC` | `#32353D` | Viền nút, chip lọc |
| `--input` | `#DADCE1` | `#2E3139` | Viền ô nhập |
| `--ring` | `#5B63E6` | `#8189F4` | Viền focus |
| `--primary` / `--primary-hover` | `#4F57D9` / `#444BC6` | `#7C83F2` / `#8E94F5` | Nút chính, icon mục nav đang chọn, switch bật, link |
| `--primary-foreground` | `#FFFFFF` | `#0D0E11` | Chữ trên `--primary` |
| `--primary-soft` / `--primary-soft-fg` | `#EEEFFD` / `#3A41B5` | indigo 14% / `#A9AEF8` | Dòng được chọn, vòng focus, khối "AI analysis" |
| `--destructive` / `--destructive-foreground` | `#D93A45` / `#FFFFFF` | `#F0616A` / `#0D0E11` | Nút xác nhận thao tác nguy hiểm |

Chỉ có **một** màu nhấn (`--primary`). Không thêm màu nhấn thứ hai cho điều hướng hay tiêu đề; mọi màu còn lại đều mang nghĩa (§3.2–3.5).

Cặp chữ/nền ở trên đạt ≥ 4,5:1, trừ `--subtle-foreground` (≈ 3:1, chỉ dùng cho nội dung không bắt buộc). Kiểm bằng axe trên `/_ui` (§10).

### 3.2 Tông trạng thái

Mọi badge, pill và callout dùng một trong bảy **tông**. Mỗi tông có bốn biến: `bg` (nền nhạt), `fg` (chữ trên nền nhạt), `border`, `solid` (chấm, icon trên bản đồ, cột biểu đồ). Badge luôn là **nền nhạt + chữ đậm màu**, không bao giờ là khối màu đặc.

| Tông | Light `bg` / `fg` / `border` / `solid` | Dark `fg` / `solid` (`bg` = `solid` 14%, `border` = `solid` 28%) |
| --- | --- | --- |
| `neutral` | `#F0F1F3` / `#4A4E57` / `#E0E2E6` / `#9AA0A9` | `#B4B8C0` / `#7C818B` (`bg` trắng 6%, `border` trắng 10%) |
| `info` | `#EBF2FE` / `#2458C6` / `#D3E2FB` / `#3B7BEA` | `#8DB6F7` / `#4E8CF0` |
| `success` | `#E8F6EE` / `#1C7548` / `#CDEBDA` / `#2FA36B` | `#6FD3A0` / `#34B275` |
| `teal` | `#E6F5F3` / `#16706A` / `#CBEAE5` / `#22A39A` | `#6ED4CB` / `#26B2A8` |
| `warning` | `#FDF4E3` / `#92560A` / `#F6E1B8` / `#E59A1A` | `#F2C274` / `#ECA62E` |
| `danger` | `#FDECED` / `#B1242E` / `#F8D2D5` / `#E0454F` | `#F7979D` / `#F0616A` |
| `progress` | `#F3EEFE` / `#6A3FC6` / `#E3D8FB` / `#8B5CF6` | `#C1A8FB` / `#9B76F8` (`bg` 15%, `border` 30%) |

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

**Mức tin cậy ETA** (DOC-23 §7.3), vẽ bằng `ConfidenceChip` dạng ba vạch cao dần (5/8/12 px):

| Giá trị | Tông | Biểu diễn không dùng màu |
| --- | --- | --- |
| `HIGH` | `success` | 3 vạch đầy |
| `MEDIUM` | `teal` | 2 vạch |
| `LOW` | `warning` | 1 vạch |
| `NONE` | `neutral` | 0 vạch, chữ "Schedule only" |

**Confidence của AI** (số trong `[0, 1]`): `≥ 0,8` → `success`; `0,6 … < 0,8` → `teal`; `< 0,6` → `warning` và luôn kèm chữ "Low confidence" (ngưỡng 0,6 lấy từ `lowConfidence` của API khi có, FR-09.6). Cùng dạng ba vạch như ETA, cộng phần trăm.

**Nguồn dữ liệu** (màu series biểu đồ, không phải tông):

| `source` | Token |
| --- | --- |
| `GTFS_RT_VEHICLE_POSITION` | `--chart-1` |
| `GTFS_RT_TRIP_UPDATE` | `--chart-2` |
| `TICKETING_SALES` | `--chart-3` |
| `TICKETING_SALE_POINTS` | `--chart-5` |
| `GTFS_STATIC` | `--chart-8` |

### 3.4 Độ trễ và bunching

Dùng cho icon xe trên bản đồ, cột trễ trong danh sách, đoạn tuyến trong `LineStrip`. Ngưỡng ±300 s khớp ngưỡng OTP mặc định (`earlyToleranceSeconds`/`lateToleranceSeconds`, DOC-23 §8).

| Lớp | Điều kiện (`delaySeconds`) | Token | Light / Dark | Chữ |
| --- | --- | --- | --- | --- |
| `early` | < −300 | `--delay-early` | `#3B7BEA` / `#4E8CF0` | "Early" |
| `on-time` | −300 … 300 | `--delay-on-time` | `#2FA36B` / `#34B275` | "On time" |
| `late` | 301 … 600 | `--delay-late` | `#E59A1A` / `#ECA62E` | "Late" |
| `very-late` | > 600 | `--delay-very-late` | `#E0454F` / `#F0616A` | "Very late" |
| `unknown` | không có | `--delay-unknown` | `#A4A8B0` / `#6E727C` | "No delay data" |

**Bunching** có màu riêng không dùng cho việc gì khác: `--bunching` `#C026D3` (dark `#D85BE6`), nền nhạt `--bunching-soft` `#FBEAFD` (dark 16%). Dùng cho halo và đường nối trên bản đồ, badge "Bunching" (`tone-bunching`), đường headway trong biểu đồ.

Cài đặt (P5-03): `--bunching` trên `--bunching-soft` chỉ đạt khoảng 4,1:1 nên chữ của badge và callout bunching dùng thêm `--bunching-fg` (light `#A21CAF`, dark `#EBA6F4`) và viền `--bunching-border`. Hai biến này, cùng `--scrim` (lớp mờ sau dialog/drawer) và nhóm `--code-*` của §5.6, nằm trong `tokens.css` ở cả hai theme.

### 3.5 Bảng màu biểu đồ

- **Phân loại** `--chart-1` … `--chart-8` (series không phải nguồn), theo thứ tự: light `#4F57D9`, `#22A39A`, `#E59A1A`, `#C026D3`, `#3B7BEA`, `#E0454F`, `#84A83A`, `#9AA0A9`; dark `#7C83F2`, `#26B2A8`, `#ECA62E`, `#D85BE6`, `#4E8CF0`, `#F0616A`, `#9BC04C`, `#7C818B`.
- **Tuần tự** `--heat-1` … `--heat-7` cho heatmap độ trễ (thấp → cao): light `#EAF6EF`, `#CFEBDC`, `#F5E9C7`, `#F3D293`, `#EDAA6E`, `#E0735D`, `#B9424C`; dark `#16302A`, `#1D4A3A`, `#4A4526`, `#6E5623`, `#8A4A26`, `#9C3A33`, `#C24650`.
- **Tuần tự** cho OTP (cao là tốt): đảo ngược bảng trên.

### 3.6 Bản đồ nền

Token `--map-*` dùng khi vẽ lớp nền tự dựng (bản đồ nhỏ, minh họa, fallback khi thiếu tile) và để chỉnh màu flavor Protomaps (§6.1):

| Token | Light | Dark |
| --- | --- | --- |
| `--map-land` | `#F1F0EB` | `#15171B` |
| `--map-water` | `#D4E3EE` | `#16263A` |
| `--map-park` | `#DDEAD4` | `#17261C` |
| `--map-block` | `#E8E6DF` | `#1B1D22` |
| `--map-road` / `--map-casing` | `#FFFFFF` / `#DEDBD2` | `#272A31` / `#1F2126` |
| `--map-label` | `#8B8F97` | `#6E727C` |

## 4. Chữ, khoảng cách, bo góc, đổ bóng, chuyển động

### 4.1 Chữ

| Token | Giá trị |
| --- | --- |
| Font chữ | `Geist Variable` (`@fontsource-variable/geist`), dự phòng `ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif` |
| Font mono | `Geist Mono Variable` (`@fontsource-variable/geist-mono`), dự phòng `ui-monospace, "SF Mono", Menlo, monospace`. Dùng cho id, topic, offset, payload, JSON, tên job, `runId` — mọi thứ người dùng có thể dán vào terminal |
| Tracking | Chữ thân −0,006 em; tiêu đề −0,015 … −0,028 em; số lớn −0,035 … −0,05 em |
| Số | `tabular-nums` cho mọi cột số, đồng hồ đếm, ETA, KPI |
| Độ đậm | 400 thường, 500 nhãn, nav, tiêu đề cột, 600 tiêu đề và số KPI, 700 số trong route shield |

Thang cỡ chữ (cỡ/line-height px):

| Vai trò | Cỡ | Độ đậm | Dùng cho |
| --- | --- | --- | --- |
| `text-xs` | 12/16 | 400–500 | Meta, tiêu đề cột, chữ trong badge |
| `label` | 12,5/18 | 500 | Nhãn trường, breadcrumb, chip lọc |
| `text-sm` | 13/19 | 400 | Chữ thân ops, ô bảng |
| `text-sm-medium` | 13,5/20 | 500 | Mục nav |
| `text-base` | 14/20 | 400 | Chữ thân mặc định; chữ thân hành khách trên mobile là 15/22 |
| `title-card` | 14/20 | 600 | Tiêu đề thẻ |
| `title-panel` | 15/22 | 600 | Tiêu đề khối, tiêu đề drawer |
| `title-page` | 24/29 | 600 | `h1` của trang |
| `kpi` | 28/30 | 600 | Số trong `KpiCard` |
| `display` | 34–44 | 600 | ETA lớn ở Stop detail, số chính ở demo console |

### 4.2 Bố cục và kích thước

| Token | Giá trị |
| --- | --- |
| Khoảng cách | Lưới 4 px (thang mặc định của Tailwind). Lề trang 28 px (≥ 1024 px), 16 px (mobile). Khoảng giữa thẻ 12–16 px. Padding thẻ 14–16 px |
| Sidebar | 232 px (DOC-34 §4.2) |
| Chiều cao điều khiển | Nút, ô nhập, mục nav 34 px; nút nhỏ và chip lọc 28 px; nút lớn 40 px; tab 38 px |
| Chiều cao dòng bảng | ≈ 40 px (padding dọc 10 px) ở ops; 48 px ở danh sách hành khách |
| Vùng chạm | ≥ 44 × 44 px ở màn hành khách trên mobile; ≥ 24 × 24 px ở ops (WCAG 2.5.8) |
| Focus | Viền `--ring` cộng vòng 3 px `--primary-soft`, chỉ khi `:focus-visible` |
| Z-index | banner 30, drawer 40, overlay/dialog 50, popover 55, toast 70 |

### 4.3 Bo góc và đổ bóng

| Token | Giá trị | Dùng cho |
| --- | --- | --- |
| `--radius-sm` | 6 px | Badge, pill, route shield |
| `--radius-md` | 9 px | Nút, ô nhập, mục nav |
| `--radius-lg` | 12 px | Thẻ, toast, khối code |
| `--radius-xl` | 16 px | Panel nổi trên bản đồ, bottom sheet; dialog 14 px |
| `--shadow-xs` | `0 1px 2px rgba(16,18,27,.05)` | Nút, ô nhập, mục nav đang chọn |
| `--shadow-sm` | `0 1px 3px rgba(16,18,27,.06), 0 1px 2px rgba(16,18,27,.03)` | Thẻ lúc nghỉ |
| `--shadow-md` | `0 6px 16px -4px rgba(16,18,27,.10), 0 2px 5px -2px rgba(16,18,27,.06)` | Panel nổi trên bản đồ, popover, toast |
| `--shadow-lg` | `0 20px 48px -12px rgba(16,18,27,.22), 0 6px 14px -6px rgba(16,18,27,.10)` | Drawer, dialog |

Dark theme dùng bóng đen đậm hơn (xem `tokens.css`). Không dùng viền trái màu để nhấn thẻ hay dòng; trạng thái đi bằng badge, chấm hoặc icon.

### 4.4 Chuyển động

- 120–250 ms `ease-out`: 150 ms cho hover/nhấn/switch, 200–250 ms cho drawer, dialog, bottom sheet. Highlight dòng mới 2 s (nền `tone-info-bg` nhạt dần).
- Mỗi loại dữ liệu live có đúng một chuyển động đặc trưng: vòng "ping" quanh xe đang chọn và quanh gián đoạn đang mở; nét đứt chạy dọc cạnh của sơ đồ pipeline khi có dữ liệu chảy; skeleton shimmer 1,6 s.
- `prefers-reduced-motion: reduce` → tắt mọi transition và animation kể trên, bản đồ dùng `jumpTo` thay cho `flyTo`, icon `LoaderCircle` đứng yên.

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
/** Route shield (signage motif, §2.1). sm 18 px, md 22 px, lg 32 px, xl 44 px high. */
interface RouteBadgeProps { routeId: string; displayName: string; color?: string; textColor?: string; size?: 'sm' | 'md' | 'lg' | 'xl' }
```

- `RouteBadge` là **route shield**: ô bo góc `--radius-sm` (xl: 11 px) nền `route_color`, chữ `displayName` đậm 700, rộng tối thiểu bằng chiều cao. Dùng `route_color`/`route_text_color` của GTFS. Nếu tương phản giữa hai màu < 4,5:1 hoặc thiếu màu thì tự chọn chữ đen hoặc trắng cho tương phản cao hơn; thiếu màu nền thì dùng màu phân loại theo hash của `routeId` (§3.5).
- `ConfidenceChip` là ba vạch cao dần kèm chữ ("High", "74%"…), không có nền; luôn có tooltip giải thích (DOC-37 §5).
- `SeverityBadge` và `StatusPill` là badge nền nhạt cao 22 px, bo 6 px, chấm hoặc icon 12 px + chữ 12 px/500 theo tông §3.2.

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

/** Dot + label: Live / Reconnecting… / Polling / Offline. Reads RealtimeProvider. Shown in the sidebar "Live feed" card. */
declare function RealtimeStatusDot(): JSX.Element;
```

`FreshnessIndicator` và `RealtimeStatusDot` là chấm 7 px có quầng 3 px (`tone-*-solid` trên `tone-*-bg`) cộng chữ `text-xs` màu `--muted-foreground`; khi stale chữ chuyển `--tone-warning-fg`.

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

/** "3 new alerts · show" pill shown above a list when realtime rows are held back (P-5). */
interface NewItemsPillProps { count: number; onShow: () => void }

/** Two to four mutually exclusive options (period, colour mode, direction). Renders role="radiogroup". */
interface SegmentedControlProps<V extends string> { options: { value: V; label: string }[]; value: V; onChange: (v: V) => void; size?: 'sm' | 'md'; label: string }
```

- Bộ lọc hiển thị dạng **chip tròn** cao 28 px viền `--border-strong`; chip đang áp giá trị đổi thành nền `--foreground` chữ `--card` và ghi giá trị ("Severity: High"); chip "+ Sale point" viền đứt để thêm bộ lọc tùy chọn. `MultiSelectFilter` mở popover danh sách checkbox từ chip.
- `DataTable`: tiêu đề cột trên dải `--surface` chữ 12 px/500 `--muted-foreground`; dòng hover nền `--surface`; dòng đang chọn nền `--primary-soft`; số canh phải.

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
/** KPI tile: label, large number (kpi type), optional unit, delta vs comparison and sparkline. */
interface KpiCardProps {
  label: string;
  value: React.ReactNode;
  unit?: string;                     // rendered small next to the value ("%", "s", "/ 642 scheduled")
  delta?: { value: string; direction: 'up' | 'down' | 'flat'; good: 'up' | 'down'; caption?: string };  // "2.4 pts" "vs last Sunday"
  sparkline?: number[];
  hint?: React.ReactNode;            // one line under the value
  tone?: Tone;                       // colours the value only
  href?: string;
}
interface DetailDrawerProps { title: React.ReactNode; open: boolean; onClose: () => void; width?: 520 | 680; footer?: React.ReactNode; children: React.ReactNode }
/** List + detail side by side inside the page (Alerts, Dead letters, Ticketing). Detail is driven by the URL id param. */
interface SplitViewProps { list: React.ReactNode; detail: React.ReactNode | null; listWidth?: number /* default 400 */; emptyDetail: React.ReactNode }
interface RequireRoleProps { role: 'viewer' | 'operator'; fallback?: React.ReactNode; children: React.ReactNode }
```

- `KpiCard` thay cho `StatCard` cũ: thẻ trắng bo 12 px, nhãn 12,5 px `--muted-foreground`, số 28 px/600; delta xanh khi đi đúng hướng `good`, đỏ khi ngược, xám khi phẳng.
- `DetailDrawer` trượt từ phải, bóng `--shadow-lg`, header có tiêu đề + badge trạng thái, footer dải `--surface` chứa nút. `SplitView` dùng cho màn hình mà người dùng duyệt nhiều bản ghi liên tiếp (DOC-34 §4.2).
- `ConfirmDialog` rộng 440 px, bo 14 px, nền overlay tối 30% có blur 2 px; nút xác nhận thao tác nguy hiểm dùng `--destructive`.
- Toast dùng `sonner` (component toast của shadcn/ui), dạng thẻ trắng bo 12 px có icon tông ở trái: thành công 4 s, lỗi 8 s và có `traceId`; tối đa 3 toast cùng lúc; `role="status"` (thành công) hoặc `role="alert"` (lỗi).
- Nút thao tác đang chạy hiện spinner và bị disable; không có nút nào gửi hai request khi bấm hai lần.

### 5.6 JSON

```ts
interface JsonViewerProps { value: string | object; maxHeight?: number; wrap?: boolean; compareTo?: string | object; fileName?: string }   // read-only, pretty-printed, mono; compareTo → line diff
interface JsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  errors?: { pointer: string; message: string }[];   // from 422 invalid-payload `errors[]`
  readOnly?: boolean;
  height?: number;                                    // default 360
}
```

- `JsonViewer` và `JsonEditor` luôn là **khối code tối** ở cả hai theme (nền `#15161B`, viền `#23252C`, số dòng `#50545E`, bo 12 px) với thanh trên ghi tên file và nút "Copy". Có `compareTo` thì hiện diff theo dòng: dòng xóa nền đỏ 14% và dấu "−", dòng thêm nền xanh 16% và dấu "+" (dùng cho "Edited" so với "Original" ở Dead letters).
- `JsonEditor` tải CodeMirror qua `React.lazy` (DOC-34 §7). Có lint JSON cú pháp phía client; lỗi schema từ server gắn vào dòng theo JSON Pointer (tìm vị trí khóa trong văn bản; không tìm được thì hiện ở danh sách dưới trình sửa).
- Phím `Esc` trong trình sửa **không** đóng drawer (tránh mất bản sửa); có nút "Discard changes" riêng.
- Cài đặt (P5-03): bảng màu khối code là token `--code-*` trong `tokens.css` (không còn mã hex trong component, DS-11). Số dòng `#50545E` chỉ đạt 2,4:1 nên được vẽ bằng nội dung sinh ra (`::before` với `data-line`): đúng màu của prototype, không nằm trong văn bản được đọc, chọn, sao chép hay kiểm tương phản.

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
interface SparklineProps { points: number[]; label: string; tone?: Tone; area?: boolean }   // plain SVG, no ECharts
```

Quy ước ở §7.

### 5.8 Bản đồ

Component ở `features/map/`, dùng chung `MapCanvas` (bọc `react-map-gl/maplibre`) cho Live map và bản đồ nhỏ ở Stop detail. Xem §6.

- **Panel nổi** (`MapFloatPanel`): nền `--card` 94% có `backdrop-filter: blur(12px)`, viền `--border`, bo `--radius-xl`, bóng `--shadow-md`; đặt cách mép bản đồ 16 px.
- **Cụm điều khiển** (`MapControls`): cột nút 34 × 34 px chung một khung bo 10 px (zoom in, zoom out, locate, legend).

### 5.9 Motif và khung trang

```ts
/** Line-and-stop strip (§2.1). Vertical in panels, horizontal in "Where" rows. */
interface LineStripProps {
  orientation?: 'vertical' | 'horizontal';
  color?: string;                                   // route colour; default --primary
  stops: {
    id: string;
    name: string;
    meta?: React.ReactNode;                          // "Transfer to 21", "Scheduled 4:31 PM"
    eta?: React.ReactNode;                           // right-aligned time or delay
    state?: 'passed' | 'current' | 'upcoming';       // passed = muted rail and dot
    major?: boolean;                                 // larger dot (terminus, interchange)
  }[];
  segments?: { from: string; to: string; delayClass: 'early' | 'on-time' | 'late' | 'very-late' | 'unknown' }[];  // horizontal only: colour runs by §3.4
  marker?: { atStopId: string; label: string };      // e.g. the selected vehicle between stops
}

/** Breadcrumb + h1 + one-line subtitle on the left, page actions on the right. */
interface PageHeaderProps { crumbs?: { label: string; href?: string }[]; title: string; subtitle?: React.ReactNode; actions?: React.ReactNode }

/** Card with optional head (title, meta, actions) and footer band. */
interface CardProps { title?: React.ReactNode; meta?: React.ReactNode; actions?: React.ReactNode; footer?: React.ReactNode; flat?: boolean; children: React.ReactNode }

/** Vertical event list with dots on a thin rail: alert activity, DLQ history, run steps. */
interface ActivityTimelineProps { items: { id: string; text: React.ReactNode; at: string; axis: TimeAxis; tone?: Tone | 'primary' | 'bunching' }[] }

/** Tinted block with icon: warnings above content, AI analysis, paused-consumer notices. */
interface CalloutProps { tone: Tone | 'primary' | 'bunching'; icon?: React.ReactNode; title?: React.ReactNode; children: React.ReactNode; action?: React.ReactNode }
```

- Khối AI (gợi ý điều phối, phân tích gián đoạn, phân loại ticketing, triage DLQ) là `Callout tone="primary"` (nền `--primary-soft`) với nhãn khối, tiêu đề một dòng, `ConfidenceChip`, danh sách lý do nếu có và dòng "{model} · {version}" ở cuối. Không dùng màu nhấn thứ hai cho AI.
- `AppSidebar`, `BottomTabs` ở `app/shell/` (DOC-34 §4.2, `screens/shell-and-navigation.md`).

## 6. Style bản đồ

### 6.1 Nền

- Style `offline` (PMTiles) hoặc `online` theo `env.js` (ADR-0021). Style `offline` dựng lúc chạy bằng `@protomaps/basemaps` (`frontend/src/features/map/baseStyle.ts`) từ flavor `light` (theme sáng) và `black` (theme tối), rồi ghi đè màu nền đất, nước, công viên, khối nhà, đường, viền đường, nhãn bằng token §3.6 để bản đồ có tông ấm nhạt thay vì xám thuần. Đổi theme gọi `map.setStyle` với style của theme mới rồi thêm lại các lớp dữ liệu.
- Nền gần như không bão hòa, nên màu tuyến và màu độ trễ nổi bật. Nhãn đường và địa danh bằng tiếng Anh (`lang: "en"`).
- Camera mặc định: fit bbox `-93.730, 44.707, -92.806, 45.330` (S-05), `minZoom` 9, `maxZoom` 18, `maxBounds` = bbox nới 10%.
- Attribution luôn hiện: "© OpenStreetMap contributors · Protomaps".

### 6.2 Các lớp dữ liệu (thứ tự từ dưới lên)

| Id lớp | Nguồn | Hiển thị |
| --- | --- | --- |
| `routes-casing`, `routes-line` | GeoJSON từ E-02 của các tuyến đang chọn | Đường màu `route_color`, rộng 3 px (zoom < 12), 5 px (≥ 12); viền 1 px màu nền. Không chọn tuyến nào thì không vẽ đường tuyến |
| `disruption-stops` | `affectedStopIds` của episode đang mở trên tuyến đã chọn | Vòng tròn viền `--delay-very-late` 3 px bán kính 9 px, nhãn "!"; vòng "ping" quanh trạm đầu tiên (§4.4) |
| `stops` | E-06 `routeId` (khi có tuyến chọn) | Chỉ từ zoom 14: chấm trắng viền màu tuyến 2,5 px bán kính 4 px; trạm được chọn bán kính 7 px viền `--primary` |
| `vehicle-clusters` | GeoJSON xe, `cluster: true`, `clusterMaxZoom: 11`, `clusterRadius: 40` | Zoom < 12: vòng tròn `--foreground` có số xe màu `--card` |
| `bunching-links` | Cặp xe của episode đang mở (viewer) | Đường đứt `--bunching` 2 px nối hai xe |
| `bunching-halo` | Hai xe của mỗi cặp | Vòng `--bunching` rộng 3 px bán kính 16 px; **không** bị gom cụm, luôn hiện ở mọi zoom |
| `vehicles` | GeoJSON xe (không thuộc cụm) | Icon mũi tên hình giọt nước xoay theo `bearing`, viền trắng 1,5 px. Màu theo chế độ "Colour vehicles by" (`colour` trên URL, `screens/live-map.md`): lớp trễ (§3.4, mặc định), màu tuyến, hoặc mức đông (`occupancyStatus` → `success`/`teal`/`warning`/`danger` solid, không có dữ liệu → `--delay-unknown`). `very-late` thêm viền ngoài thứ hai. Không có `bearing` thì dùng hình tròn |
| `vehicle-selected` | Xe đang chọn | Vòng `--primary` 3 px bán kính 14 px và vòng "ping" (§4.4) |
| `vehicle-labels` | Xe | Zoom ≥ 14: nhãn tuyến (`displayName`) cạnh icon, chữ 11 px có halo |

- **Xe cũ:** `eventTimestamp` cũ hơn 90 s so với `businessNow` → độ mờ 0,4 và tooltip "Last seen 2 min ago". Cũ hơn 5 phút → ẩn (DOC-33 §5.1).
- **Chuyển động:** vị trí mới áp ngay (không nội suy) để khớp dữ liệu; gom mọi thay đổi trong một khung hình thành một lần `setData` (DOC-34 §7).
- **Tô nổi khi chọn cặp bunching** (`bunching=<id>` trên URL): camera fit hai xe với padding 120 px, các xe khác độ mờ 0,5.
- Lớp `vehicles` dùng `symbol` với icon SDF đăng ký lúc tải style (`map.addImage(..., { sdf: true })`) để đổi màu bằng `icon-color`, không tạo ảnh cho từng màu.

### 6.3 Chú giải

Chú giải là panel nổi thu gọn được ở góc dưới trái (mở mặc định trên desktop, đóng trên mobile; nút "Legend" bật/tắt), liệt kê theo chế độ màu đang chọn: năm lớp trễ, hoặc các tuyến đang hiện, hoặc các mức đông; thêm cặp bunching (chỉ viewer), gián đoạn/trạm bị ảnh hưởng, xe cũ, cụm xe. Mỗi dòng có **số xe hiện tại** của lớp đó (đếm phía client từ dữ liệu đang vẽ). Chú giải là bảng HTML, đọc được bằng screen reader.

## 7. Quy ước biểu đồ

- ECharts import theo module (`echarts/core` + `LineChart`, `BarChart`, `HeatmapChart`, `GridComponent`, `TooltipComponent`, `LegendComponent`, `VisualMapComponent`, `DataZoomComponent`, `CanvasRenderer`). Không import `echarts` đầy đủ.
- Hai theme `pti-light`, `pti-dark` đăng ký từ token: font Geist 12 px, nhãn trục `--muted-foreground`, lưới ngang `--border` nét đứt, không kẻ lưới dọc, tooltip nền `--popover` viền `--border` bóng `--shadow-md`.
- Series chính vẽ đường 2 px có vùng tô bên dưới cùng màu độ mờ 8–12%; series so sánh ("Typical Sunday", "Expected range") là nét đứt `--muted-foreground` hoặc dải nền `--muted`. Ngưỡng ("Alert threshold", "On-time window") là đường đứt có nhãn nhỏ ở mép phải.
- Trục thời gian: nhãn và tooltip định dạng bằng hàm của `src/lib/time.ts` theo múi giờ agency (ECharts không hỗ trợ múi giờ tùy ý). Tooltip ghi đủ ngày, giờ và viết tắt múi giờ.
- Trục số bắt đầu từ 0 với biểu đồ cột và số đếm; biểu đồ độ trễ có đường tham chiếu tại 0 và tại ±300 s (nét đứt, ghi "On-time window").
- Giá trị thiếu (`null`) để trống, không nối đường qua chỗ thiếu (`connectNulls: false`), để thấy được khoảng consumer dừng (E-31).
- Mỗi biểu đồ có tiêu đề (heading HTML, không dùng title của ECharts), `caption` tóm tắt bằng chữ (`aria-describedby`) và nút "View as table" chuyển sang bảng số liệu tương đương.
- Không dùng biểu đồ tròn, 3D hay hiệu ứng animation khi cập nhật dữ liệu (`animation: false` khi refetch; chỉ animate lần vẽ đầu nếu không reduced motion).
- Kích thước: chiều rộng theo khung chứa (`ResizeObserver`), chiều cao cố định theo loại (timeline 200–240 px, heatmap 7 × 16–24 ô, sparkline 24–40 px).
- Heatmap: ô bo 3 px cách nhau 2 px, màu `--heat-1…7`, chú giải "Less" → "More late" ở góc phải.

## 8. Tiêu chí a11y

Mục tiêu **WCAG 2.2 AA** cho màn hành khách (NFR-11) và cùng tiêu chí, ở mức nỗ lực tốt nhất, cho ops console.

| Tiêu chí | Cách đáp ứng |
| --- | --- |
| Tương phản (1.4.3, 1.4.11) | Chữ ≥ 4,5:1, thành phần UI và focus ring ≥ 3:1 trên cả hai theme; axe kiểm trên `/_ui` |
| Không chỉ dùng màu (1.4.1) | Severity, trạng thái, mức tin cậy, lớp trễ luôn có chữ hoặc icon (§3.3, §3.4); bản đồ có chế độ danh sách |
| Bàn phím (2.1.1, 2.4.7) | Mọi thao tác làm được bằng bàn phím; focus luôn thấy được; không bẫy focus trừ dialog/drawer (Radix quản lý và trả focus về nút mở) |
| Bỏ qua khối lặp (2.4.1) | Link "Skip to content" là phần tử focus đầu tiên |
| Landmark và heading | `nav` sidebar (`aria-label` "Primary"), `main`, một `h1` mỗi trang; nhóm nav có tiêu đề ("Network", "Analytics", "Operations") |
| Thông báo động (4.1.3) | Toast qua `role="status"`/`role="alert"`. Alert mới trên màn Alerts: vùng `aria-live="polite"` đọc tóm tắt "2 new alerts". Không đọc cập nhật vị trí xe |
| Bản đồ | Canvas có `aria-label` "Live vehicle map"; điều khiển zoom/pan bằng bàn phím (MapLibre `keyboard: true`); **chế độ danh sách** (`view=list`) liệt kê xe theo tuyến với cùng thông tin |
| Kích thước vùng chạm (2.5.8) | §4.2 |
| Phóng to (1.4.4, 1.4.10) | Không mất nội dung khi zoom 200%; màn hành khách reflow ở 320 px |
| Chuyển động (2.3.3) | Tôn trọng `prefers-reduced-motion` (§4.4) |
| Form (3.3.1, 3.3.2) | Mọi ô có `label`; lỗi gắn bằng `aria-describedby` và `aria-invalid`; lỗi từ server (`errors[].field`) gắn vào đúng ô |
| Bảng | `<table>` có `caption`, `th scope`; bảng virtualize có `aria-rowcount`/`aria-rowindex` |
| Ngôn ngữ | `<html lang="en">` |
| Biểu đồ | `caption` và "View as table" (§7) |

## 9. Dark mode

- Chọn trong menu tài khoản ở chân sidebar (anonymous: nút theme ở chân sidebar): "Light", "Dark", "System" (mặc định).
- Dark là theme hạng nhất, không phải bản đảo màu: nền `#0D0E11`, bề mặt `#16171C`, tông trạng thái dùng nền trong suốt 14% (§3.2). Demo console mặc định dark (DOC-48 §4).
- Mọi token ở §3 có giá trị dark. Không component nào dùng màu cố định ngoài token.
- Bản đồ đổi sang style dark; biểu đồ đổi theme ECharts mà không mất trạng thái zoom.

## 10. Trang `/_ui`

Route chỉ có trong bản build dev (`import.meta.env.DEV`) hoặc bản build đặt `VITE_PTI_UI_CATALOG=true` (build của `pnpm e2e`, để chạy axe); image `pti-frontend` không đặt biến này nên trả 404. URL là `/_ui` đúng nghĩa đen (file route `[_]ui.tsx`), đáp ứng P5-03. Bố cục như artifact "PTI Design System" (DR-88): trang bìa, bảng màu, thang chữ, rồi liệt kê mọi component ở §5 với mọi biến thể (mọi severity, mọi `(domain, status)`, mọi mức tin cậy, mọi lớp trễ, `ErrorState` cho từng slug của DOC-30, bảng 10.000 dòng giả), cạnh nhau ở hai theme. Playwright chạy axe trên trang này. Bảng 10.000 dòng giả (`DataTable`) và `JsonEditor` thuộc P5-10 nên chưa có trong trang.

## 11. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| DS-01 | Axe trên `/_ui` ở light và dark | Không có vi phạm `serious`/`critical` (gồm `color-contrast`) |
| DS-02 | Component: `StatusPill` với mọi giá trị enum của DOC-15 | Đúng tông, icon, nhãn DOC-37; giá trị lạ → `neutral` + giá trị thô |
| DS-03 | Component: `ConfidenceChip` `value=0.59` không có `lowConfidence`; `value=0.82` với `lowConfidence=true` | Lần 1 hiện "Low confidence"; lần 2 cũng hiện (ưu tiên cờ của API) |
| DS-04 | Unit: lớp trễ tại −301, −300, 300, 301, 600, 601, `null` | `early`, `on-time`, `on-time`, `late`, `late`, `very-late`, `unknown` |
| DS-05 | Unit: `RouteBadge` với `color=FFFF00`, `textColor=FFFFFF` | Chữ đổi sang đen (tương phản ≥ 4,5:1) |
| DS-11 | Unit: đọc `tokens.css`; lint tìm mã hex trong `src/` | Mọi token ở §3 có giá trị ở cả `:root` và `.dark`; không có mã hex nào ngoài `tokens.css` và bảng màu code của `JsonViewer` |
| DS-06 | Component: `DataTable` virtual 10.000 dòng, `j`/`k`/`Enter` | Chỉ ≤ 60 dòng trong DOM; `aria-rowindex` đúng; `Enter` gọi `onRowOpen` |
| DS-07 | Component: `ConfirmDialog` với `reason` min 3, `onConfirm` ném `ApiError` | Nút confirm disable khi lý do < 3 ký tự; lỗi hiện trong dialog, dialog không đóng |
| DS-08 | Component: `JsonEditor` với `errors=[{pointer:'/payload/route_id'}]` | Dòng chứa khóa `route_id` có đánh dấu lỗi và thông điệp |
| DS-09 | Component: `prefers-reduced-motion` | Không có class transition; `LoaderCircle` không có animation |
| DS-10 | Component: `FreshnessIndicator` `asOf` cũ hơn `staleAfterSeconds` | Tông `warning`, chữ "Updated … ago" vẫn hiện |

## 12. Câu hỏi còn mở

Không có.
