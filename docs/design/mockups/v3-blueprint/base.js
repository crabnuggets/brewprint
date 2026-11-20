// Drawing helpers for the v3 blueprint boards. Sample data matches v2: a ten-pulse, 20 g, 1:15 brew (30 g a pulse, 300 g in all).
const NS = 'http://www.w3.org/2000/svg';
const CUM = [30, 60, 90, 120, 150, 180, 210, 240, 270, 300];
const PLAN = [0, 30, 45, 60, 75, 90, 105, 120, 135, 150];
const POUR = 5;
const ACTUAL = [0, 30, 45, 60, 75, 99, 128, 159, 186, 216];
const REACHED = [30.8, 61.2, 91.0, 121.1, 151.6, 182.0, 211.7, 242.3, 272.9, 304.2];

document.querySelectorAll('.screen').forEach(s => {
  if (!s.querySelector('.status')) s.prepend(document.getElementById('st').content.cloneNode(true));
});

function el(tag, attrs, parent) { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.appendChild(e); return e; }
function txt(parent, x, y, s, anchor, style) {
  const e = el('text', { x, y, 'text-anchor': anchor || 'start', 'font-size': 12.5, style: 'font-family:inherit;' + (style || 'fill:var(--dim)') }, parent);
  e.textContent = s; return e;
}
function mmss(s) { return Math.floor(s / 60) + ':' + String(Math.round(s % 60)).padStart(2, '0'); }
const smooth = f => f * f * (3 - 2 * f) * .35 + f * .65;
let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
let uid = 0;

function weightAt(t, starts, reached, upto) {
  let w = 0;
  for (let i = 0; i < upto; i++) {
    const a = starts[i], b = a + POUR + 1, base = i ? reached[i - 1] : 0, top = reached[i];
    if (t >= b) w = top; else if (t > a) w = base + (top - base) * smooth((t - a) / (b - a));
  }
  return w + Math.sin(t * 5.1) * .3 * (w > 0);
}

function hatch(svg, angle, gap, style) {
  const id = 'h' + (++uid);
  const defs = el('defs', {}, svg);
  const p = el('pattern', { id, width: gap, height: gap, patternUnits: 'userSpaceOnUse', patternTransform: `rotate(${angle})` }, defs);
  el('line', { x1: 0, y1: 0, x2: 0, y2: gap, style }, p);
  return `url(#${id})`;
}

// Live and record water charts. data-look picks each direction's drawing conventions.
document.querySelectorAll('svg.chart').forEach(svg => {
  const W = +svg.dataset.w, record = svg.dataset.mode === 'record', look = svg.dataset.look;
  const H = record ? 128 : 104, B = record ? 18 : 2, ph = H - B - 4;
  svg.setAttribute('width', W); svg.setAttribute('height', H); svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  const span = record ? 270 : PLAN[9] + POUR + 30, t = +(svg.dataset.t || span);
  const labelW = 46, pw = W - labelW;
  const X = s => s / span * pw, Y = g => 4 + ph - Math.max(0, g) / 320 * ph;
  const starts = record ? ACTUAL : PLAN, upto = record ? 10 : starts.filter(s => s <= t).length;
  if (look === 'a') { // drafting grid inside the chart
    for (let x = 0; x <= pw; x += 16) el('line', { x1: x, x2: x, y1: 4, y2: 4 + ph, style: 'stroke:var(--grid)' }, svg);
    for (let y = 4 + ph; y >= 4; y -= 16) el('line', { x1: 0, x2: pw, y1: y, y2: y, style: 'stroke:var(--grid)' }, svg);
  }
  if (!record) {
    const fill = look === 'c' ? hatch(svg, 45, 3.6, 'stroke:var(--plan);stroke-width:1') : null;
    for (let s = 1; s <= t; s += 1) {
      const f = Math.max(0, weightAt(s, starts, REACHED, upto) - weightAt(s - 1, starts, REACHED, upto));
      if (f > .8) { const h = Math.min(f, 8) / 8 * ph / 3; el('rect', { x: X(s - 1), y: 4 + ph - h, width: Math.max(X(1) - .6, 1), height: h, style: fill ? `fill:${fill}` : 'fill:var(--flow)' }, svg); }
    }
  }
  el('line', { x1: 0, x2: pw, y1: 4 + ph, y2: 4 + ph, style: 'stroke:var(--hair)', 'stroke-width': 1 }, svg);
  if (record) {
    for (let s = 0; s <= 240; s += 60) txt(svg, X(s), H - 3, mmss(s), s ? 'middle' : 'start');
    let d = `M0,${Y(0)}`; PLAN.forEach((p, i) => { d += ` L${X(p)},${Y(i ? CUM[i - 1] : 0)} L${X(p + POUR)},${Y(CUM[i])}`; }); d += ` L${X(span)},${Y(300)}`;
    if (look === 'c') el('path', { d: d + ` L${X(span)},${Y(0)} Z`, style: `fill:${hatch(svg, 45, 4, 'stroke:var(--faint);stroke-width:1')};stroke:none` }, svg);
    else el('path', { d, fill: 'none', style: 'stroke:var(--plan)', 'stroke-width': look === 'a' ? 1.4 : 3, 'stroke-dasharray': look === 'a' ? '5 3' : 'none', 'stroke-linejoin': 'round' }, svg);
  }
  el('line', { x1: 0, x2: pw - 4, y1: Y(300), y2: Y(300), style: 'stroke:var(--faint)', 'stroke-width': 1.6, 'stroke-dasharray': '1.5 4', 'stroke-linecap': 'round' }, svg);
  txt(svg, W, Y(300) + 4, '300 g', 'end');
  if (svg.dataset.target) {
    const g = +svg.dataset.target, c = look === 'b' ? 'var(--acc)' : 'var(--dash)';
    el('line', { x1: 0, x2: pw - 4, y1: Y(g), y2: Y(g), style: `stroke:${c}`, 'stroke-width': 1.4, 'stroke-dasharray': '6 4' }, svg);
    txt(svg, W, Y(g) + 4, g + ' g', 'end', `fill:${c};font-weight:650`);
  }
  let d = ''; for (let s = 0; s <= t + 1e-6; s += .5) d += (s ? ' L' : 'M') + X(s).toFixed(1) + ',' + Y(weightAt(s, starts, REACHED, upto)).toFixed(1);
  el('path', { d, fill: 'none', style: 'stroke:var(--ink)', 'stroke-width': 2.2, 'stroke-linejoin': 'round' }, svg);
  if (!record) { const y = Y(weightAt(t, starts, REACHED, upto)); el('circle', { cx: X(t), cy: y, r: 4, style: 'fill:var(--ink);stroke:var(--paper)', 'stroke-width': 2 }, svg); }
});

// A: grinder dial elevation with dash-dot centre lines.
document.querySelectorAll('svg.dial').forEach(svg => {
  const c = 50, r = 38, set = +svg.dataset.v;
  svg.setAttribute('viewBox', '0 0 100 100'); svg.setAttribute('width', 100); svg.setAttribute('height', 100);
  const st = 'stroke:var(--frame);fill:none';
  el('line', { x1: 2, x2: 98, y1: c, y2: c, style: st, 'stroke-width': .9, 'stroke-dasharray': '9 3 2 3' }, svg);
  el('line', { y1: 2, y2: 98, x1: c, x2: c, style: st, 'stroke-width': .9, 'stroke-dasharray': '9 3 2 3' }, svg);
  el('circle', { cx: c, cy: c, r, style: st, 'stroke-width': 1.3 }, svg);
  el('circle', { cx: c, cy: c, r: 12, style: st, 'stroke-width': 1.1 }, svg);
  for (let i = 0; i < 50; i++) {
    const a = i / 50 * Math.PI * 2 - Math.PI / 2, l = i % 5 ? 4 : 8;
    el('line', { x1: c + Math.cos(a) * (r - l), y1: c + Math.sin(a) * (r - l), x2: c + Math.cos(a) * r, y2: c + Math.sin(a) * r, style: st, 'stroke-width': i % 5 ? .8 : 1.2 }, svg);
  }
  const a = set / 50 * Math.PI * 2 - Math.PI / 2;
  el('line', { x1: c + Math.cos(a) * 12, y1: c + Math.sin(a) * 12, x2: c + Math.cos(a) * (r - 10), y2: c + Math.sin(a) * (r - 10), style: 'stroke:var(--ink)', 'stroke-width': 2.4, 'stroke-linecap': 'round' }, svg);
  el('path', { d: `M${c - 4} 3 L${c + 4} 3 L${c} 10 Z`, style: 'fill:var(--ink)' }, svg);
});

// A: the pour progress drawn as a dimension line, filled up to the scale reading.
document.querySelectorAll('svg.dim').forEach(svg => {
  const W = +svg.dataset.w, f = +svg.dataset.f, y = 16, x0 = 1, x1 = W - 1, xf = x0 + (x1 - x0) * f;
  svg.setAttribute('width', W); svg.setAttribute('height', 28); svg.setAttribute('viewBox', `0 0 ${W} 28`);
  const st = 'stroke:var(--frame)';
  el('line', { x1: x0, x2: x0, y1: 4, y2: 26, style: st, 'stroke-width': 1.2 }, svg);
  el('line', { x1: x1, x2: x1, y1: 4, y2: 26, style: st, 'stroke-width': 1.2 }, svg);
  el('line', { x1: x0, x2: xf, y1: y, y2: y, style: 'stroke:var(--ink)', 'stroke-width': 3.5 }, svg);
  el('line', { x1: xf, x2: x1, y1: y, y2: y, style: st, 'stroke-width': 1.2, 'stroke-dasharray': '4 3' }, svg);
  el('path', { d: `M${x1} ${y} l-9 -3.5 v7 Z`, style: 'fill:var(--frame)' }, svg);
  el('line', { x1: xf - 5, y1: y + 6, x2: xf + 5, y2: y - 6, style: 'stroke:var(--ink)', 'stroke-width': 1.8 }, svg);
});

// A: the plan as a ruler; one bay per pulse, filled as pulses complete.
document.querySelectorAll('svg.ruler').forEach(svg => {
  const W = +svg.dataset.w, done = +svg.dataset.done, part = +svg.dataset.part, n = 10, bw = W / n;
  svg.setAttribute('width', W); svg.setAttribute('height', 30); svg.setAttribute('viewBox', `0 0 ${W} 30`);
  const st = 'stroke:var(--frame)';
  el('line', { x1: 0, x2: W, y1: 22, y2: 22, style: st, 'stroke-width': 1.2 }, svg);
  for (let i = 0; i <= n; i++) {
    const x = Math.min(Math.max(i * bw, .6), W - .6);
    el('line', { x1: x, x2: x, y1: i % 5 ? 12 : 6, y2: 26, style: st, 'stroke-width': 1.2 }, svg);
    if (i < n) el('line', { x1: x + bw / 2, x2: x + bw / 2, y1: 18, y2: 22, style: st, 'stroke-width': .8 }, svg);
  }
  for (let i = 0; i < done; i++) el('rect', { x: i * bw + 3, y: 14, width: bw - 6, height: 6, style: 'fill:var(--ink)' }, svg);
  el('rect', { x: done * bw + 3, y: 14, width: (bw - 6) * part, height: 6, style: 'fill:var(--ink)' }, svg);
});

// A: cyanotype photogram of beans and a cup ring, the shapes a sunprint leaves pale.
document.querySelectorAll('svg.photo').forEach(svg => {
  svg.setAttribute('viewBox', '0 0 300 300');
  const g = el('g', { style: 'filter:url(#soft)' }, svg);
  el('circle', { cx: 190, cy: 150, r: 84, style: 'fill:none;stroke:var(--photo)', 'stroke-width': 13 }, g);
  [[80, 210, 30], [118, 250, -20], [60, 260, 75], [150, 222, 110], [40, 175, -45]].forEach(([x, y, a]) => {
    const b = el('g', { transform: `translate(${x} ${y}) rotate(${a})` }, g);
    el('ellipse', { rx: 13, ry: 18, style: 'fill:var(--photo)' }, b);
    el('path', { d: 'M0 -16 C-6 -6 6 6 0 16', style: 'fill:none;stroke:var(--paper)', 'stroke-width': 2.4, opacity: .8 }, b);
  });
});

// C: stipple for the coffee bed in Fig. 1.
document.querySelectorAll('[data-stipple]').forEach(g => {
  const [x0, y0, x1, y1, bl, br] = g.dataset.stipple.split(',').map(Number);
  for (let i = 0; i < 420; i++) {
    const y = y0 + rnd() * (y1 - y0), k = (y - y0) / (y1 - y0);
    const l = x0 + (bl - x0) * k, r = x1 + (br - x1) * k, x = l + rnd() * (r - l);
    if (rnd() < .35 + k * .5) el('circle', { cx: x.toFixed(1), cy: y.toFixed(1), r: (.55 + rnd() * .5).toFixed(2), style: 'fill:var(--ink)' }, g);
  }
});
