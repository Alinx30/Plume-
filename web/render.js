// Dessin d'une page Plume (même rendu que l'appli Android), en coordonnées de page.
export const PAGE_W = 1000;
export const PAGE_H = 1414;

export const PAPERS = [
  ['BLANK', 'Blanc'], ['LINED', 'Lignes'], ['LINED_WIDE', 'Grandes lignes'], ['SEYES', 'Seyès'],
  ['GRID', 'Quadrillage'], ['GRID_SMALL', 'Petits carreaux'], ['MILLIMETER', 'Millimétré'],
  ['DOTS', 'Points'], ['CORNELL', 'Cornell'], ['MUSIC', 'Portées'],
];
export const PAPER_COLORS = [
  [0xFFFFFFFF, 'Blanc'], [0xFFFBF5E6, 'Crème'], [0xFFFFF7C2, 'Jaune'],
  [0xFFE9EEF3, 'Gris bleu'], [0xFF232733, 'Ardoise'], [0xFF121212, 'Noir'],
];
export const INK_COLORS = [0xFF1B1B1F, 0xFF1E4FD8, 0xFFD32F2F, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00, 0xFF00897B, 0xFF8D6E63];
export const HIGHLIGHTER_COLORS = [0xFFFFE135, 0xFF7CFC4A, 0xFFFF5FA2, 0xFF4FC3F7, 0xFFFFA94D, 0xFFB388FF];
export const FOLDER_COLORS = [0xFF3F5AA8, 0xFFE07A3F, 0xFF3E9C74, 0xFFB0457E, 0xFF7A5BC4, 0xFFC9A227, 0xFF4E8FB8, 0xFF8C6B4F];

/** Couleur Android (entier ARGB) vers CSS. */
export function css(argb, alphaOverride) {
  const n = Number(argb);
  const a = alphaOverride ?? (Math.floor(n / 0x1000000) & 0xFF) / 255;
  const r = (n >> 16) & 0xFF, g = (n >> 8) & 0xFF, b = n & 0xFF;
  return `rgba(${r},${g},${b},${a.toFixed(3)})`;
}

export function isDark(color) {
  const n = Number(color);
  const r = ((n >> 16) & 0xFF) / 255, g = ((n >> 8) & 0xFF) / 255, b = (n & 0xFF) / 255;
  return r * 0.299 + g * 0.587 + b * 0.114 < 0.5;
}

export const pressureWidth = (base, p) => base * (0.55 + 0.9 * Math.min(1, Math.max(0, p)));

function line(ctx, color, width = 1.6) { ctx.strokeStyle = color; ctx.lineWidth = width; }
function hLines(ctx, start, step, end = PAGE_H, x0 = 0, x1 = PAGE_W) {
  ctx.beginPath();
  for (let y = start; y < end; y += step) { ctx.moveTo(x0, y); ctx.lineTo(x1, y); }
  ctx.stroke();
}
function vLines(ctx, start, step, end = PAGE_W) {
  ctx.beginPath();
  for (let x = start; x < end; x += step) { ctx.moveTo(x, 0); ctx.lineTo(x, PAGE_H); }
  ctx.stroke();
}
function seg(ctx, x0, y0, x1, y1) { ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x1, y1); ctx.stroke(); }

export function drawPaper(ctx, style, color) {
  const dark = isDark(color);
  ctx.fillStyle = css(color, 1);
  ctx.fillRect(0, 0, PAGE_W, PAGE_H);
  const lineC = dark ? 'rgba(255,255,255,0.27)' : '#C9D7E8';
  const strongC = dark ? 'rgba(255,255,255,0.47)' : '#9DB2CC';
  const marginC = dark ? 'rgba(255,138,128,0.6)' : '#EFB4B0';
  const mm = 1000 / 210;
  switch (style) {
    case 'LINED': line(ctx, lineC); hLines(ctx, 150, 44, PAGE_H - 40); line(ctx, marginC, 2); seg(ctx, 110, 0, 110, PAGE_H); break;
    case 'LINED_WIDE': line(ctx, lineC); hLines(ctx, 160, 60, PAGE_H - 40); line(ctx, marginC, 2); seg(ctx, 110, 0, 110, PAGE_H); break;
    case 'SEYES':
      line(ctx, dark ? 'rgba(176,184,255,0.2)' : '#D3D5EE', 1.2); hLines(ctx, 2 * mm, 2 * mm);
      line(ctx, dark ? 'rgba(176,184,255,0.4)' : '#A3A8DA', 1.8); hLines(ctx, 8 * mm, 8 * mm); vLines(ctx, 8 * mm, 8 * mm);
      line(ctx, marginC, 2); seg(ctx, 40 * mm, 0, 40 * mm, PAGE_H); break;
    case 'GRID': line(ctx, lineC); vLines(ctx, 40, 40); hLines(ctx, 40, 40); break;
    case 'GRID_SMALL': line(ctx, lineC); vLines(ctx, 5 * mm, 5 * mm); hLines(ctx, 5 * mm, 5 * mm); break;
    case 'MILLIMETER':
      line(ctx, dark ? 'rgba(255,183,77,0.13)' : 'rgba(240,168,104,0.33)', 0.8); vLines(ctx, mm, mm); hLines(ctx, mm, mm);
      line(ctx, dark ? 'rgba(255,183,77,0.27)' : 'rgba(240,168,104,0.6)', 1.2); vLines(ctx, 5 * mm, 5 * mm); hLines(ctx, 5 * mm, 5 * mm);
      line(ctx, dark ? 'rgba(255,183,77,0.47)' : '#E59B57', 1.8); vLines(ctx, 10 * mm, 10 * mm); hLines(ctx, 10 * mm, 10 * mm); break;
    case 'DOTS':
      ctx.fillStyle = dark ? 'rgba(255,255,255,0.4)' : '#B4C2D3';
      for (let y = 40; y < PAGE_H; y += 40) for (let x = 40; x < PAGE_W; x += 40) { ctx.beginPath(); ctx.arc(x, y, 2.4, 0, 6.2832); ctx.fill(); }
      break;
    case 'CORNELL': {
      const top = 130, bottom = PAGE_H - 260;
      line(ctx, lineC); hLines(ctx, top + 44, 44, bottom, 280);
      line(ctx, strongC, 2); seg(ctx, 0, top, PAGE_W, top); seg(ctx, 280, top, 280, bottom); seg(ctx, 0, bottom, PAGE_W, bottom);
      break;
    }
    case 'MUSIC':
      line(ctx, dark ? 'rgba(255,255,255,0.53)' : '#8C96A8', 1.6);
      for (let y = 120; y + 48 < PAGE_H - 60; y += 118) for (let k = 0; k < 5; k++) seg(ctx, 60, y + k * 12, PAGE_W - 60, y + k * 12);
      break;
  }
}

/** Courbe lissée passant par les milieux (comme Android). */
export function tracePath(ctx, p) {
  ctx.beginPath();
  if (p.length < 3) return;
  ctx.moveTo(p[0], p[1]);
  if (p.length === 3) { ctx.lineTo(p[0] + 0.1, p[1]); return; }
  for (let i = 3; i + 1 < p.length; i += 3) {
    const px = p[i - 3], py = p[i - 2], x = p[i], y = p[i + 1];
    ctx.quadraticCurveTo(px, py, (px + x) / 2, (py + y) / 2);
  }
  ctx.lineTo(p[p.length - 3], p[p.length - 2]);
}

export function drawStroke(ctx, s, fade = 1) {
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  ctx.lineWidth = s.width;
  const hl = s.tool === 'HIGHLIGHTER';
  ctx.strokeStyle = css(s.color, (hl ? 0x70 / 255 : 1) * fade);
  tracePath(ctx, s.points);
  ctx.stroke();
}

export function wrapText(ctx, t) {
  ctx.font = `${t.size}px "Roboto", "Segoe UI", system-ui, sans-serif`;
  const lines = [];
  for (const para of (t.text || '').split('\n')) {
    const words = para.split(/(\s+)/);
    let cur = '';
    for (const w of words) {
      const next = cur + w;
      if (cur && ctx.measureText(next).width > t.width && w.trim()) { lines.push(cur.trimEnd()); cur = w.trimStart(); }
      else cur = next;
    }
    lines.push(cur);
  }
  return lines;
}

export function textHeight(ctx, t) { return Math.max(t.size, wrapText(ctx, t).length * t.size * 1.17); }

export function drawText(ctx, t) {
  const lines = wrapText(ctx, t);
  ctx.fillStyle = css(t.color);
  ctx.textBaseline = 'top';
  lines.forEach((l, i) => ctx.fillText(l, t.x, t.y + i * t.size * 1.17 + t.size * 0.08));
}

/** Rectangle centré qui garde les proportions de l'image (fond PDF). */
export function fitRect(w, h) {
  const ratio = h / w;
  if (ratio >= PAGE_H / PAGE_W) { const dw = PAGE_H / ratio; return [(PAGE_W - dw) / 2, 0, dw, PAGE_H]; }
  const dh = PAGE_W * ratio; return [0, (PAGE_H - dh) / 2, PAGE_W, dh];
}

/**
 * Dessine une page entière. `images` fournit les images prêtes (asset -> ImageBitmap/HTMLImageElement)
 * et `pdfImage` l'image de la page PDF de fond si elle existe.
 */
export function drawPage(ctx, page, paperStyle, paperColor, { getImage, pdfImage, exclude, replay } = {}) {
  if (page.pdf) {
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, PAGE_W, PAGE_H);
    if (pdfImage) { const [x, y, w, h] = fitRect(pdfImage.width, pdfImage.height); ctx.drawImage(pdfImage, x, y, w, h); }
  } else drawPaper(ctx, paperStyle, paperColor);
  for (const im of page.images || []) {
    if (exclude?.has(im.id)) continue;
    const img = getImage?.(im.asset);
    if (img) ctx.drawImage(img, im.x, im.y, im.width, im.height);
    else { ctx.fillStyle = '#EDEDED'; ctx.fillRect(im.x, im.y, im.width, im.height); }
  }
  const fade = (s) => (replay && s.rec === replay.rec && s.t > replay.pos ? 0.18 : 1);
  for (const s of page.strokes || []) if (s.tool === 'HIGHLIGHTER' && !exclude?.has(s.id)) drawStroke(ctx, s, fade(s));
  for (const t of page.texts || []) if (!exclude?.has(t.id)) drawText(ctx, t);
  for (const s of page.strokes || []) if (s.tool !== 'HIGHLIGHTER' && !exclude?.has(s.id)) drawStroke(ctx, s, fade(s));
}
