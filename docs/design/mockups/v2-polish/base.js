// Drawing helpers for the v2 polish boards. Sample data: a ten-pulse, 20 g, 1:15 brew (30 g a pulse, 300 g in all).
const NS = 'http://www.w3.org/2000/svg';
const CUM = [30, 60, 90, 120, 150, 180, 210, 240, 270, 300];
const PLAN = [0, 30, 45, 60, 75, 90, 105, 120, 135, 150];
const POUR = 5;
// The saved record: pulses 6 to 10 started late, as in the owner's brew.
const ACTUAL = [0, 30, 45, 60, 75, 99, 128, 159, 186, 216];
const REACHED = [30.8, 61.2, 91.0, 121.1, 151.6, 182.0, 211.7, 242.3, 272.9, 304.2];

document.querySelectorAll('.screen').forEach(s => {
  if (!s.querySelector('.status')) s.prepend(document.getElementById('st').content.cloneNode(true));
});

function el(tag, attrs, parent) { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.appendChild(e); return e; }
function txt(parent, x, y, s, anchor, style) { const e = el('text', { x, y, 'text-anchor': anchor || 'start', 'font-size': 12, 'font-family': 'Roboto Flex, Segoe UI, sans-serif', style: style || 'fill:var(--dim)' }, parent); e.textContent = s; return e; }
function mmss(s) { return Math.floor(s / 60) + ':' + String(Math.round(s % 60)).padStart(2, '0'); }
const smooth = f => f * f * (3 - 2 * f) * .35 + f * .65;

function weightAt(t, starts, reached, upto) {
  let w = 0;
  for (let i = 0; i < upto; i++) {
    const a = starts[i], b = a + POUR + 1, base = i ? reached[i - 1] : 0, top = reached[i];
    if (t >= b) w = top; else if (t > a) w = base + (top - base) * smooth((t - a) / (b - a));
  }
  return w + Math.sin(t * 5.1) * .3 * (w > 0);
}

document.querySelectorAll('svg.chart').forEach(svg => {
  const W = +svg.dataset.w, record = svg.dataset.mode === 'record';
  const H = record ? 132 : 104, B = record ? 18 : 2, ph = H - B - 4;
  svg.setAttribute('width', W); svg.setAttribute('height', H); svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  const span = record ? 270 : PLAN[9] + POUR + 30, t = +(svg.dataset.t || span);
  const labelW = 46, pw = W - labelW;
  const X = s => s / span * pw, Y = g => 4 + ph - Math.max(0, g) / 320 * ph;
  const starts = record ? ACTUAL : PLAN, upto = record ? 10 : starts.filter(s => s <= t).length;
  // Flow band along the bottom third (live only).
  if (!record) for (let s = 1; s <= t; s += 1) {
    const f = Math.max(0, weightAt(s, starts, REACHED, upto) - weightAt(s - 1, starts, REACHED, upto));
    if (f > .8) { const h = Math.min(f, 8) / 8 * ph / 3; el('rect', { x: X(s - 1), y: 4 + ph - h, width: Math.max(X(1) - .6, 1), height: h, style: 'fill:var(--flow)' }, svg); }
  }
  el('line', { x1: 0, x2: pw, y1: 4 + ph, y2: 4 + ph, style: 'stroke:var(--hair)', 'stroke-width': 1 }, svg);
  if (record) {
    for (let s = 0; s <= 240; s += 60) txt(svg, X(s), H - 3, mmss(s), s ? 'middle' : 'start');
    // The plan as written: rising during each planned pour, flat during each wait.
    let d = `M0,${Y(0)}`; PLAN.forEach((p, i) => { d += ` L${X(p)},${Y(i ? CUM[i - 1] : 0)} L${X(p + POUR)},${Y(CUM[i])}`; }); d += ` L${X(span)},${Y(300)}`;
    el('path', { d, fill: 'none', style: 'stroke:var(--plan)', 'stroke-width': 3, 'stroke-linejoin': 'round' }, svg);
  }
  // Total brew water: faint dotted. Current target: darker dashed. Each labelled at its right end.
  el('line', { x1: 0, x2: pw - 4, y1: Y(300), y2: Y(300), style: 'stroke:var(--faint)', 'stroke-width': 1.6, 'stroke-dasharray': '1.5 4', 'stroke-linecap': 'round' }, svg);
  txt(svg, W, Y(300) + 4, '300 g', 'end', 'fill:var(--dim)');
  if (svg.dataset.target) {
    const g = +svg.dataset.target;
    el('line', { x1: 0, x2: pw - 4, y1: Y(g), y2: Y(g), style: 'stroke:var(--dash)', 'stroke-width': 1.4, 'stroke-dasharray': '6 4' }, svg);
    txt(svg, W, Y(g) + 4, g + ' g', 'end', 'fill:var(--title);font-weight:650');
  }
  let d = ''; for (let s = 0; s <= t + 1e-6; s += .5) d += (s ? ' L' : 'M') + X(s).toFixed(1) + ',' + Y(weightAt(s, starts, REACHED, upto)).toFixed(1);
  el('path', { d, fill: 'none', style: 'stroke:var(--title)', 'stroke-width': 2.2, 'stroke-linejoin': 'round' }, svg);
  if (!record) { const y = Y(weightAt(t, starts, REACHED, upto)); el('circle', { cx: X(t), cy: y, r: 4, style: 'fill:var(--title);stroke:var(--surface)', 'stroke-width': 2 }, svg); }
});
