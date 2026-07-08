// Drawing tool built from scratch on HTML5 Canvas — no third-party libraries.
// Deliberately independent of the networking layer so it can be tested or
// reused on its own: callers pull PNG snapshots out via snapshot().

export interface DrawingTool {
  setColor(color: string): void;
  setBrushSize(px: number): void;
  undo(): void;
  clear(): void;
  /** Current canvas as a PNG data URL. */
  snapshot(): string;
  destroy(): void;
}

interface Point {
  x: number;
  y: number;
}

interface Stroke {
  color: string;
  size: number;
  points: Point[];
}

export function createDrawingTool(canvas: HTMLCanvasElement): DrawingTool {
  const maybeCtx = canvas.getContext('2d');
  if (!maybeCtx) throw new Error('Canvas 2D context unavailable');
  const ctx = maybeCtx;

  const strokes: Stroke[] = [];
  let current: Stroke | null = null;
  let color = '#18181b';
  let size = 5;

  // Map pointer coordinates to canvas pixels (canvas is CSS-scaled to fit)
  function toCanvas(e: PointerEvent): Point {
    const rect = canvas.getBoundingClientRect();
    return {
      x: (e.clientX - rect.left) * (canvas.width / rect.width),
      y: (e.clientY - rect.top) * (canvas.height / rect.height),
    };
  }

  function drawDot(p: Point, strokeColor: string, strokeSize: number): void {
    ctx.fillStyle = strokeColor;
    ctx.beginPath();
    ctx.arc(p.x, p.y, strokeSize / 2, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawSegment(from: Point, to: Point, strokeColor: string, strokeSize: number): void {
    ctx.strokeStyle = strokeColor;
    ctx.lineWidth = strokeSize;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(from.x, from.y);
    ctx.lineTo(to.x, to.y);
    ctx.stroke();
  }

  // Full redraw from the stroke list — the basis for undo and clear
  function repaint(): void {
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    for (const stroke of strokes) {
      const pts = stroke.points;
      drawDot(pts[0], stroke.color, stroke.size);
      for (let i = 1; i < pts.length; i++) {
        drawSegment(pts[i - 1], pts[i], stroke.color, stroke.size);
      }
    }
  }

  function onPointerDown(e: PointerEvent): void {
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    canvas.setPointerCapture(e.pointerId);
    const p = toCanvas(e);
    current = { color, size, points: [p] };
    strokes.push(current);
    drawDot(p, color, size);
  }

  function onPointerMove(e: PointerEvent): void {
    if (!current) return;
    const p = toCanvas(e);
    const prev = current.points[current.points.length - 1];
    current.points.push(p);
    drawSegment(prev, p, current.color, current.size);
  }

  function onPointerUp(): void {
    current = null;
  }

  canvas.addEventListener('pointerdown', onPointerDown);
  canvas.addEventListener('pointermove', onPointerMove);
  canvas.addEventListener('pointerup', onPointerUp);
  canvas.addEventListener('pointercancel', onPointerUp);

  repaint(); // white background so snapshots aren't transparent

  return {
    setColor(c) {
      color = c;
    },
    setBrushSize(px) {
      size = px;
    },
    undo() {
      strokes.pop();
      current = null;
      repaint();
    },
    clear() {
      strokes.length = 0;
      current = null;
      repaint();
    },
    snapshot() {
      return canvas.toDataURL('image/png');
    },
    destroy() {
      canvas.removeEventListener('pointerdown', onPointerDown);
      canvas.removeEventListener('pointermove', onPointerMove);
      canvas.removeEventListener('pointerup', onPointerUp);
      canvas.removeEventListener('pointercancel', onPointerUp);
    },
  };
}
