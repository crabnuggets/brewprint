// Shared drawing helpers for the scale proposal boards. Sample data only.
const NS = 'http://www.w3.org/2000/svg';
const S = [10, 35, 15, 20, 15, 20, 15, 20, 15];
const POURS = [45, 50, 50, 50, 45];
const CUM = [45, 95, 145, 195, 240];

document.querySelectorAll('.screen').forEach(s => {
  if (!s.closest('.land') && !s.querySelector('.status')) s.prepend(document.getElementById('st').content.cloneNode(true));
});
document.querySelectorAll('.plan').forEach(el => {
  let t = +el.dataset.t, h = '';
  S.forEach((d, i) => { const f = Math.max(0, Math.min(1, t / d)); t -= d; h += `<i class="${i % 2 ? '' : 'po'}" style="flex:${d}"><em style="width:${f * 100}%"></em></i>`; });
  el.innerHTML = h;
});

// Pour starts in seconds. "actual" moves pulse 4 early and pulse 5 late, as in the accepted timer boards.
function starts(actual) { return actual === 'skip' ? [0, 45, 80, 103, 138] : actual ? [0, 45, 80, 103, 153] : [0, 45, 80, 115, 150]; }
const DUR = [10, 15, 15, 15, 15];
const OVER = [0.8, 1.0, 1.1, 0.9, 1.3];
function weightAt(t, actual) {
  const st = starts(actual); let w = 0;
  for (let i = 0; i < 5; i++) {
    const a = st[i], b = a + DUR[i], base = i ? CUM[i - 1] + OVER[i - 1] : 0, top = CUM[i] + OVER[i];
    if (t >= b) w = top; else if (t > a) { const f = (t - a) / DUR[i]; w = base + (top - base) * (f * f * (3 - 2 * f) * .35 + f * .65); }
  }
  return w + Math.sin(t * 7.3) * .25;
}
function series(T, actual) {
  const pts = []; for (let t = 0; t <= T + 1e-6; t += .5) pts.push([t, Math.max(0, weightAt(t, actual))]);
  const fl = pts.map((p, i) => { const a = pts[Math.max(0, i - 3)], b = pts[Math.min(pts.length - 1, i + 3)]; return [p[0], Math.max(0, (b[1] - a[1]) / (b[0] - a[0] || 1))]; });
  return { pts, fl };
}
function el(tag, attrs, parent) { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.appendChild(e); return e; }
function txt(parent, x, y, s, anchor, size, fill) { const e = el('text', { x, y, 'text-anchor': anchor || 'start', 'font-size': size || 10.5, fill: fill || '#595B66', 'font-family': 'Roboto Flex, Segoe UI, sans-serif' }, parent); e.textContent = s; return e; }
function mmss(s) { return Math.floor(s / 60) + ':' + String(Math.round(s % 60)).padStart(2, '0'); }

document.querySelectorAll('svg.ch').forEach(svg => {
  const W = +svg.dataset.w, H = +svg.dataset.h, t = +svg.dataset.t, T = +(svg.dataset.span || 210);
  const actual = svg.dataset.actual === 'skip' ? 'skip' : svg.dataset.actual !== undefined, flow = svg.dataset.flow !== undefined;
  const from = +(svg.dataset.from || 0);
  svg.setAttribute('width', W); svg.setAttribute('height', H); svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  const L = 30, R = flow ? 30 : 8, Tp = 8, B = 20, pw = W - L - R, ph = H - Tp - B;
  const X = s => L + (s - from) / (T - from) * pw, Y = g => Tp + ph - g / 260 * ph;
  const fh = ph * .34, FY = v => Tp + ph - Math.min(v, 8) / 8 * fh;
  [0, 80, 160, 240].forEach(g => { el('line', { x1: L, x2: W - R, y1: Y(g), y2: Y(g), stroke: '#E0E1E7', 'stroke-width': 1 }, svg); txt(svg, L - 6, Y(g) + 3.5, g, 'end'); });
  if (flow) { [4, 8].forEach(v => txt(svg, W - R + 6, FY(v) + 3.5, v)); txt(svg, W - R + 6, FY(0) + 3.5, '0'); }
  const step = (T - from) > 150 ? 60 : 30;
  for (let s = Math.ceil(from / step) * step; s <= T; s += step) txt(svg, X(s), H - 5, mmss(s), 'middle');
  if (svg.dataset.targets) {
    const which = svg.dataset.targets === 'all' ? CUM : svg.dataset.targets.split(',').map(Number);
    which.forEach(g => { el('line', { x1: L, x2: W - R, y1: Y(g), y2: Y(g), stroke: '#898B96', 'stroke-width': 1.2, 'stroke-dasharray': '4 4' }, svg); });
  }
  const { pts, fl } = series(t, actual);
  const vis = p => p[0] >= from;
  if (flow) {
    const f = fl.filter(vis); let d = `M${X(f[0][0])},${FY(0)}`; f.forEach(p => d += ` L${X(p[0]).toFixed(1)},${FY(p[1]).toFixed(1)}`); d += ` L${X(f[f.length - 1][0])},${FY(0)} Z`;
    el('path', { d, fill: '#C9CAD3', opacity: .75 }, svg);
  }
  const w = pts.filter(vis); let d = ''; w.forEach((p, i) => d += (i ? ' L' : 'M') + X(p[0]).toFixed(1) + ',' + Y(p[1]).toFixed(1));
  el('path', { d, fill: 'none', stroke: '#121316', 'stroke-width': 2.2, 'stroke-linejoin': 'round' }, svg);
  if (svg.dataset.dot !== undefined) { const p = w[w.length - 1]; el('line', { x1: X(p[0]), x2: X(p[0]), y1: Tp, y2: Tp + ph, stroke: '#898B96', 'stroke-dasharray': '2 3' }, svg); el('circle', { cx: X(p[0]), cy: Y(p[1]), r: 4.5, fill: '#121316', stroke: '#fff', 'stroke-width': 2 }, svg); }
});

// Ring dial: data-f = fraction of the target reached (may exceed 1), data-mode="count" draws a draining wait ring.
document.querySelectorAll('.dial').forEach(d => {
  const svg = el('svg', { viewBox: '0 0 300 300', width: 300, height: 300 }); d.prepend(svg);
  const r = 134, c = 2 * Math.PI * r, f = +d.dataset.f;
  for (let i = 0; i < 60; i++) { const a = i / 60 * 2 * Math.PI - Math.PI / 2, major = i % 15 === 0; const r1 = 150, r2 = major ? 141 : 145;
    el('line', { x1: 150 + Math.cos(a) * r1, y1: 150 + Math.sin(a) * r1, x2: 150 + Math.cos(a) * r2, y2: 150 + Math.sin(a) * r2, stroke: '#C9CAD3', 'stroke-width': major ? 2 : 1 }, svg); }
  el('circle', { cx: 150, cy: 150, r, fill: 'none', stroke: '#E4E5EA', 'stroke-width': 16 }, svg);
  const dash = d.dataset.mode === 'count' ? '#898B96' : '#121316';
  el('circle', { cx: 150, cy: 150, r, fill: 'none', stroke: dash, 'stroke-width': 16, 'stroke-linecap': 'round', 'stroke-dasharray': `${Math.min(f, 1) * c} ${c}`, transform: 'rotate(-90 150 150)' }, svg);
  if (f > 1) el('circle', { cx: 150, cy: 150, r: r - 13, fill: 'none', stroke: '#121316', 'stroke-width': 5, 'stroke-linecap': 'round', 'stroke-dasharray': `${(f - 1) * c} ${c}`, transform: 'rotate(-90 150 150)' }, svg);
  if (d.dataset.warn) { const a = +d.dataset.warn * 2 * Math.PI - Math.PI / 2; el('line', { x1: 150 + Math.cos(a) * 124, y1: 150 + Math.sin(a) * 124, x2: 150 + Math.cos(a) * 144, y2: 150 + Math.sin(a) * 144, stroke: '#fff', 'stroke-width': 3 }, svg); }
});

// Ratio gauge for landscape: data-v current ratio, data-target target ratio, scale 0..20.
document.querySelectorAll('svg.gauge').forEach(svg => {
  const S0 = 150, SW = 240, cx = 160, cy = 150, r = 118, v = +svg.dataset.v, tg = +svg.dataset.target;
  const ang = x => (S0 + SW * x / 20) * Math.PI / 180; const pt = (a, rr) => [cx + Math.cos(a) * rr, cy + Math.sin(a) * rr];
  const arc = (x0, x1, rr) => { const [ax, ay] = pt(ang(x0), rr), [bx, by] = pt(ang(x1), rr); return `M${ax},${ay} A${rr},${rr} 0 ${SW * (x1 - x0) / 20 > 180 ? 1 : 0} 1 ${bx},${by}`; };
  el('path', { d: arc(0, 20, r), fill: 'none', stroke: '#E4E5EA', 'stroke-width': 10, 'stroke-linecap': 'round' }, svg);
  el('path', { d: arc(0, v, r), fill: 'none', stroke: '#121316', 'stroke-width': 10, 'stroke-linecap': 'round' }, svg);
  for (let i = 0; i <= 80; i++) { const x = i / 4, major = i % 20 === 0; const [ax, ay] = pt(ang(x), r - 16), [bx, by] = pt(ang(x), r - (major ? 32 : 22)); el('line', { x1: ax, y1: ay, x2: bx, y2: by, stroke: x <= v ? '#121316' : '#C9CAD3', 'stroke-width': major ? 3 : 1.2 }, svg); if (major) { const [lx, ly] = pt(ang(x), r - 46); txt(svg, lx, ly + 4, x, 'middle', 11.5); } }
  const [mx, my] = pt(ang(tg), r + 12), [nx, ny] = pt(ang(tg), r - 6); el('line', { x1: mx, y1: my, x2: nx, y2: ny, stroke: '#121316', 'stroke-width': 3, 'stroke-linecap': 'round' }, svg);
  const [tx, ty] = pt(ang(tg), r + 24); txt(svg, tx + 8, ty + 4, '1:' + tg, 'middle', 13, '#121316').setAttribute('font-weight', 600);
});
