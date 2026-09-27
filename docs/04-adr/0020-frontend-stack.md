# ADR-0020: Stack frontend

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-44, DR-46, DR-48, DR-61, ADR-0017, ADR-0021, ADR-0027, DOC-11, DOC-26 §8, DOC-34, DOC-35, FR-11, NFR-11, NFR-12

## Bối cảnh

Dashboard gồm hai nhóm màn hình rất khác nhau:

- **Hành khách** (anonymous, điện thoại): bản đồ xe thời gian thực, giờ đến tại trạm. Cần tải nhanh trên 4G (NFR-12: LCP < 2,5 giây) và đạt WCAG 2.2 AA (NFR-11).
- **Ops console** (viewer, operator, desktop): bảng dữ liệu lớn (DLQ ≥ 10.000 dòng), bộ lọc đồng bộ lên URL, biểu đồ chuỗi thời gian, form có kiểm tra, sửa JSON, cập nhật trực tiếp qua SSE.

Ràng buộc chung:

- Một người phát triển; ưu tiên thư viện phổ biến, có type tốt, ít phải tự viết.
- Type của API sinh từ `openapi.json` (DR-44), sự kiện SSE có JSON Schema (DOC-33 §6).
- Đăng nhập OIDC + PKCE với Keycloak (ADR-0017), token chỉ giữ trong bộ nhớ (DOC-27 §3.2).
- Chạy offline khi demo (ADR-0021); build ra file tĩnh, phục vụ bằng nginx.
- UI tiếng Anh, chuỗi gom một chỗ (DR-48).

## Các phương án

1. **Next.js (App Router, SSR).** Mạnh cho SEO và trang nội dung; ở đây không cần SEO, SSR thêm một tiến trình Node trong compose và làm phức tạp việc giữ token trong bộ nhớ.
2. **Angular.** Đầy đủ tính năng nhưng nặng cho một người, hệ sinh thái bản đồ và biểu đồ ít lựa chọn hơn.
3. **SvelteKit.** Nhỏ và nhanh; hệ sinh thái bảng dữ liệu, OIDC và component có a11y kém phong phú hơn React.
4. **SPA React 19 + Vite**, với TanStack Router/Query/Table/Virtual, Tailwind + shadcn/ui, ECharts, MapLibre, react-hook-form + zod, CodeMirror 6, `react-oidc-context`, Zustand.

## Quyết định

Chọn **phương án 4** (đúng DR-46). Phiên bản ở DOC-11.

| Việc | Thư viện | Lý do chọn |
| --- | --- | --- |
| Build, dev server | Vite, TypeScript `strict`, pnpm | Nhanh; build ra file tĩnh; proxy `/api` khi dev (DOC-38) |
| Routing | TanStack Router (định nghĩa route theo file) | Search params có kiểu và kiểm bằng zod: bộ lọc nằm trên URL, chia sẻ được (DOC-34 §4) |
| Dữ liệu server | TanStack Query | Cache theo key, `setQueryData`/`invalidateQueries` từ sự kiện SSE (DOC-26 §9), `keepPreviousData` khi đổi bộ lọc |
| Client API | `openapi-typescript` + `openapi-fetch` | Type sinh từ `openapi.json`; sai hợp đồng thì `tsc` báo lỗi (ADR-0027) |
| Realtime | `@microsoft/fetch-event-source` | SSE gửi được header `Authorization` (DR-41) |
| Bảng | TanStack Table + TanStack Virtual | Headless, virtualize được 10.000 dòng |
| Component | Tailwind CSS 4 + shadcn/ui (Radix) | Component có sẵn a11y (focus, ARIA, bàn phím); mã nằm trong repo nên sửa được |
| Biểu đồ | Apache ECharts (`echarts-for-react`), import theo module | Heatmap, chuỗi thời gian, sparkline; canvas nên nhanh với nhiều điểm |
| Bản đồ | MapLibre GL JS + `react-map-gl/maplibre` + `pmtiles` | ADR-0021 |
| Form | react-hook-form + zod | Schema dùng chung cho kiểm tra form và search params |
| Sửa JSON | CodeMirror 6 (`@uiw/react-codemirror`, `@codemirror/lang-json`) | Nhẹ hơn Monaco nhiều, có lint JSON |
| Đăng nhập | `react-oidc-context` + `oidc-client-ts` | PKCE, silent renew, `InMemoryWebStorage` |
| State UI | Zustand | Chỉ cho state không thuộc server (panel đang mở, lựa chọn trên bản đồ, theme) |
| Test | Vitest, React Testing Library, MSW, Playwright + `@axe-core/playwright` | Unit, component, mock API từ ví dụ DOC-32, E2E, a11y |

Quy tắc đi kèm:

- **Không** dùng thư viện state khác cho dữ liệu server; dữ liệu server chỉ nằm trong TanStack Query.
- **Không** gọi `fetch` trực tiếp trong component; mọi request đi qua `src/api/` (client sinh ra và các query factory).
- Chuỗi hiển thị chỉ nằm trong `src/i18n/en.ts` (DR-48); ESLint cấm chuỗi JSX literal ngoài file này (`i18next/no-literal-string` hoặc rule tương đương, cho phép dấu câu và ký hiệu).
- Code-split theo route: màn hành khách không tải ECharts, CodeMirror hay TanStack Table.

## Hệ quả

**Tích cực**

- Một ngôn ngữ, một bộ type từ API tới component; đổi hợp đồng API làm build frontend fail thay vì lỗi lúc chạy.
- Bộ lọc trên URL giúp chia sẻ link và giữ trạng thái khi tải lại (J-3 bước 2).
- Build tĩnh: image nginx khoảng 30 MB, không có tiến trình Node lúc chạy.

**Tiêu cực**

- Nhiều thư viện phải giữ đồng bộ phiên bản (Dependabot, DOC-41).
- shadcn/ui là mã sao chép vào repo: cập nhật từ upstream phải làm tay.
- SPA không render phía server: lần tải đầu phụ thuộc kích thước bundle; phải giữ ngân sách bundle của màn hành khách (DOC-34 §7).
