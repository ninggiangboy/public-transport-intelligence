// Route-label atlas for the 3D vehicle layer (DR-111, screens/live-map §3.1). One canvas holds up to 64 labels in
// a grid of 64x64 cells, uploaded as a single texture. The shader draws each disc as `mix(white, tint, texel.r)`,
// so the canvas paints the inverse of the final look: a white disc with dark text. Bookkeeping (`cellOf`) is pure,
// which lets the tests allocate cells in jsdom without a canvas; only `upload` touches the DOM.

const DEFAULT_CELL_PX = 64;
const DEFAULT_COLUMNS = 8;
const DEFAULT_ROWS = 8;
const OVERFLOW_LABEL = '…';

export type LabelPainter = (ctx: CanvasRenderingContext2D, cell: number, label: string) => void;

export interface LabelAtlasOptions {
  /** Replaces the default white-disc/dark-text painter (used by tests and by a themed atlas). */
  draw?: LabelPainter | undefined;
  cellPx?: number | undefined;
  columns?: number | undefined;
  rows?: number | undefined;
  createCanvas?: (() => HTMLCanvasElement) | undefined;
}

/** The default cell: a white disc with dark text, tinted by the vehicle colour in the shader. */
function paintDisc(ctx: CanvasRenderingContext2D, cell: number, label: string, cellPx: number, columns: number): void {
  const x = (cell % columns) * cellPx;
  const y = Math.floor(cell / columns) * cellPx;
  const cx = x + cellPx / 2;
  const cy = y + cellPx / 2;
  ctx.clearRect(x, y, cellPx, cellPx);
  ctx.fillStyle = 'white';
  ctx.beginPath();
  ctx.arc(cx, cy, cellPx * 0.42, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = 'black';
  ctx.font = `600 ${cellPx * 0.42}px system-ui, sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(label, cx, cy);
}

export class LabelAtlas {
  readonly columns: number;
  readonly rows: number;
  /** Total cells, including the shared overflow cell at the end. */
  readonly capacity: number;

  private readonly cellPx: number;
  private readonly painter: LabelPainter;
  private readonly createCanvas: () => HTMLCanvasElement;
  private readonly cellByLabel = new Map<string, number>();
  private readonly labelByCell: (string | undefined)[];
  private next = 0;
  private needsUpload = true;
  private canvas: HTMLCanvasElement | null = null;
  private texture: WebGLTexture | null = null;

  constructor(options: LabelAtlasOptions = {}) {
    this.cellPx = options.cellPx ?? DEFAULT_CELL_PX;
    this.columns = options.columns ?? DEFAULT_COLUMNS;
    this.rows = options.rows ?? DEFAULT_ROWS;
    this.capacity = this.columns * this.rows;
    this.createCanvas = options.createCanvas ?? (() => document.createElement('canvas'));
    const cellPx = this.cellPx;
    const columns = this.columns;
    this.painter =
      options.draw ??
      ((ctx, cell, label) => {
        paintDisc(ctx, cell, label, cellPx, columns);
      });
    this.labelByCell = new Array<string | undefined>(this.capacity).fill(undefined);
    // The last cell is shared by every label that does not fit, so it is never handed out below.
    this.labelByCell[this.capacity - 1] = OVERFLOW_LABEL;
  }

  /** True when a new label was allocated since the last {@link upload}. */
  get dirty(): boolean {
    return this.needsUpload;
  }

  /** The cell of `label`, allocating one the first time; labels past capacity share the "…" cell. */
  cellOf(label: string): number {
    const existing = this.cellByLabel.get(label);
    if (existing !== undefined) return existing;
    if (this.next < this.capacity - 1) {
      const cell = this.next++;
      this.cellByLabel.set(label, cell);
      this.labelByCell[cell] = label;
      this.needsUpload = true;
      return cell;
    }
    return this.capacity - 1;
  }

  /** Paint every allocated cell into `ctx`; `upload` passes the atlas canvas, tests pass a stub. */
  paint(ctx: CanvasRenderingContext2D): void {
    for (let cell = 0; cell < this.capacity; cell++) {
      const label = this.labelByCell[cell];
      if (label !== undefined) this.painter(ctx, cell, label);
    }
  }

  /** Paint and upload the canvas as the label texture. Returns the texture, or null without a 2D context. */
  upload(gl: WebGL2RenderingContext): WebGLTexture | null {
    const canvas = this.ensureCanvas();
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    this.paint(ctx);
    const texture = this.texture ?? gl.createTexture();
    this.texture = texture;
    gl.bindTexture(gl.TEXTURE_2D, texture);
    // Row 0 of the canvas must stay v=0 so the cell rows line up with the shader; the vertex shader flips the
    // quad's y instead. Set it explicitly because other layers may have left the pixel store changed.
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.needsUpload = false;
    return texture;
  }

  dispose(gl: WebGL2RenderingContext): void {
    if (this.texture) gl.deleteTexture(this.texture);
    this.texture = null;
  }

  private ensureCanvas(): HTMLCanvasElement {
    if (this.canvas) return this.canvas;
    const canvas = this.createCanvas();
    canvas.width = this.columns * this.cellPx;
    canvas.height = this.rows * this.cellPx;
    this.canvas = canvas;
    return canvas;
  }
}
