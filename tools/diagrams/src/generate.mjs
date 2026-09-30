#!/usr/bin/env node
// SafeRoute diagram generator (Plan v7 §17.7).
//
// Turns a small JSON description (docs/diagrams/NN-topic.json) into:
//   NN-topic.excalidraw  editable at excalidraw.com
//   NN-topic.svg         clean vector export (linked from Notion / PRs)
//   NN-topic.png         raster export rendered from the SVG with resvg (2x scale)
//
// Usage (from tools/diagrams):
//   pnpm generate                         all specs in docs/diagrams/
//   pnpm generate ../../docs/diagrams/01-prompt-workflow.json
//   pnpm check                            fail if .excalidraw/.svg are out of date (no PNG)
//
// Output is deterministic (stable ids, seeds and text wrapping) so regeneration
// produces no diff unless the spec changed.

import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_SPEC_DIR = resolve(HERE, "../../../docs/diagrams");

// Role colours (Excalidraw palette): stroke / fill.
export const ROLES = {
  app: { label: "App / UI", stroke: "#1971c2", fill: "#a5d8ff" },
  service: { label: "Service", stroke: "#2f9e44", fill: "#b2f2bb" },
  data: { label: "Data", stroke: "#e8590c", fill: "#ffd8a8" },
  security: { label: "Security / emergency", stroke: "#e03131", fill: "#ffc9c9" },
  infra: { label: "Infra / workflow", stroke: "#9c36b5", fill: "#eebefa" },
  external: { label: "External", stroke: "#495057", fill: "#e9ecef" },
};

const FONT_FAMILY = "Helvetica, Arial, sans-serif";
const EDGE_COLOR = "#495057";
const TEXT_COLOR = "#1e1e1e";
const FRAME_COLOR = "#868e96";
const MAX_NODES = 20;

// ─── Spec validation ─────────────────────────────────────────────────────────

export function validateSpec(spec, file = "spec") {
  const errors = [];
  const warnings = [];
  if (typeof spec.title !== "string" || !spec.title.trim()) errors.push("title is required");
  if (spec.direction && !["TB", "LR"].includes(spec.direction)) errors.push(`direction must be "TB" or "LR"`);
  if (!Array.isArray(spec.nodes) || spec.nodes.length === 0) errors.push("nodes must be a non-empty array");
  if (spec.edges && !Array.isArray(spec.edges)) errors.push("edges must be an array");
  const groupIds = new Set((spec.groups ?? []).map((g) => g.id));
  for (const g of spec.groups ?? []) {
    if (!g.id || !g.label) errors.push(`group needs id and label: ${JSON.stringify(g)}`);
  }
  const ids = new Set();
  for (const n of spec.nodes ?? []) {
    if (!n.id || typeof n.id !== "string") errors.push(`node without id: ${JSON.stringify(n)}`);
    else if (ids.has(n.id)) errors.push(`duplicate node id "${n.id}"`);
    ids.add(n.id);
    if (!n.label) errors.push(`node "${n.id}" has no label`);
    if (!ROLES[n.role]) errors.push(`node "${n.id}" has unknown role "${n.role}" (use ${Object.keys(ROLES).join(", ")})`);
    if (n.group !== undefined && !groupIds.has(n.group)) errors.push(`node "${n.id}" references unknown group "${n.group}"`);
    if (n.rank !== undefined && !(Number.isInteger(n.rank) && n.rank >= 0)) errors.push(`node "${n.id}" rank must be a non-negative integer`);
  }
  for (const e of spec.edges ?? []) {
    if (!ids.has(e.from)) errors.push(`edge from unknown node "${e.from}"`);
    if (!ids.has(e.to)) errors.push(`edge to unknown node "${e.to}"`);
  }
  if ((spec.nodes ?? []).length > MAX_NODES) warnings.push(`${spec.nodes.length} nodes; keep diagrams under ~${MAX_NODES} (Plan v7 §17.7)`);
  if (errors.length) throw new Error(`${file}:\n  - ${errors.join("\n  - ")}`);
  return warnings;
}

// ─── Text measurement (deterministic estimate for Helvetica/Arial) ───────────

function charWidth(ch) {
  if (" ".includes(ch)) return 0.28;
  if ("il.,:;'|!Ijft()[]/".includes(ch)) return 0.3;
  if ("r-".includes(ch)) return 0.36;
  if ("mwMW@".includes(ch)) return 0.86;
  if (ch >= "A" && ch <= "Z") return 0.68;
  if (ch >= "0" && ch <= "9") return 0.56;
  if ("→←↓↑·–—".includes(ch)) return 0.7;
  return 0.54;
}

export function textWidth(text, fontSize) {
  let w = 0;
  for (const ch of text) w += charWidth(ch);
  return w * fontSize;
}

export function wrapText(text, maxWidth, fontSize) {
  const out = [];
  for (const para of String(text).split("\n")) {
    let line = "";
    for (const word of para.split(/\s+/).filter(Boolean)) {
      const candidate = line ? `${line} ${word}` : word;
      if (line && textWidth(candidate, fontSize) > maxWidth) {
        out.push(line);
        line = word;
      } else {
        line = candidate;
      }
    }
    out.push(line);
  }
  return out;
}

// ─── Layout ──────────────────────────────────────────────────────────────────
// Layered layout: rank = longest path from a source (overridable per node with
// "rank"). Direction TB puts ranks in rows; LR puts ranks in columns. Groups
// become swim lanes (columns in TB, rows in LR) drawn as dashed frames, so
// frames never overlap. Inside a lane, nodes of the same rank are packed and
// pulled towards the centre of their same-lane parents.

export function layout(spec) {
  const dir = spec.direction ?? "TB";
  const L = spec.layout ?? {};
  const cfg = {
    nodeWidth: L.nodeWidth ?? 240,
    fontSize: L.fontSize ?? 16,
    lineHeight: 1.3,
    padX: 14,
    padY: 12,
    minHeight: 56,
    nodeGap: L.nodeGap ?? 26,
    rankGap: L.rankGap ?? (dir === "TB" ? 60 : 110),
    laneGap: L.laneGap ?? 36,
    lanePad: 18,
    laneLabel: 30,
    margin: 40,
    titleHeight: spec.subtitle ? 74 : 52,
  };
  const framed = (spec.groups ?? []).length > 0;

  // Node boxes.
  const nodes = new Map();
  for (const [i, n] of spec.nodes.entries()) {
    const lines = wrapText(n.label, cfg.nodeWidth - 2 * cfg.padX, cfg.fontSize);
    const h = Math.max(cfg.minHeight, Math.ceil(lines.length * cfg.fontSize * cfg.lineHeight + 2 * cfg.padY));
    nodes.set(n.id, { ...n, order: i, lines, w: cfg.nodeWidth, h, preds: [], succs: [] });
  }
  const edges = (spec.edges ?? []).map((e, i) => ({ ...e, index: i }));
  for (const e of edges) {
    nodes.get(e.to).preds.push(e.from);
    nodes.get(e.from).succs.push(e.to);
  }

  // Ranks (longest path, with cycle detection).
  const state = new Map();
  const rankOf = (id) => {
    const n = nodes.get(id);
    if (n.rank !== undefined && state.get(id) === "done") return n.rank;
    if (state.get(id) === "visiting") throw new Error(`cycle detected at node "${id}"; set an explicit "rank"`);
    state.set(id, "visiting");
    let r = 0;
    if (n.rank !== undefined) r = n.rank;
    else for (const p of n.preds) r = Math.max(r, rankOf(p) + 1);
    n.rank = r;
    state.set(id, "done");
    return r;
  };
  for (const id of nodes.keys()) rankOf(id);
  const maxRank = Math.max(...[...nodes.values()].map((n) => n.rank));

  // Lanes.
  const lanes = (spec.groups ?? []).map((g) => ({ ...g, framed: true }));
  if (!framed || [...nodes.values()].some((n) => n.group === undefined)) lanes.push({ id: "__none", label: "", framed: false });
  for (const n of nodes.values()) n.lane = n.group ?? "__none";
  const cell = (laneId, r) =>
    [...nodes.values()].filter((n) => n.lane === laneId && n.rank === r).sort((a, b) => a.order - b.order);

  // "along" = axis inside a lane/cell (x for TB, y for LR); "flow" = rank axis.
  const along = (n) => (dir === "TB" ? n.w : n.h);
  const flow = (n) => (dir === "TB" ? n.h : n.w);
  const padAlong = (lane) => (lane.framed ? cfg.lanePad : 0);
  const labelFlow = framed ? cfg.laneLabel : 0; // room for frame labels (TB: on top of each column)
  const labelAlong = (lane) => (lane.framed && dir === "LR" ? cfg.laneLabel : 0);

  // Size of each rank along the flow axis (max over all lanes).
  const rankSize = [];
  for (let r = 0; r <= maxRank; r++) {
    rankSize[r] = Math.max(0, ...[...nodes.values()].filter((n) => n.rank === r).map(flow));
  }
  // Size of each lane along its axis (widest cell).
  for (const lane of lanes) {
    let widest = 0;
    for (let r = 0; r <= maxRank; r++) {
      const c = cell(lane.id, r);
      if (c.length) widest = Math.max(widest, c.reduce((s, n) => s + along(n), 0) + cfg.nodeGap * (c.length - 1));
    }
    lane.inner = widest;
    lane.size = widest + 2 * padAlong(lane) + labelAlong(lane);
  }

  const top = cfg.margin + cfg.titleHeight;
  const left = cfg.margin;
  const flowPadStart = framed ? cfg.lanePad + (dir === "TB" ? labelFlow : 0) : 0;
  const flowPadEnd = framed ? cfg.lanePad : 0;

  // Flow-axis offsets of ranks.
  const rankStart = [];
  let f = (dir === "TB" ? top : left) + flowPadStart;
  for (let r = 0; r <= maxRank; r++) {
    rankStart[r] = f;
    f += rankSize[r] + cfg.rankGap;
  }
  const flowEnd = f - cfg.rankGap + flowPadEnd;
  const flowBegin = dir === "TB" ? top : left;

  // Along-axis offsets of lanes, then place nodes lane by lane, rank by rank.
  let a = dir === "TB" ? left : top;
  for (const lane of lanes) {
    lane.start = a;
    const contentStart = a + padAlong(lane) + labelAlong(lane);
    const contentEnd = contentStart + lane.inner;
    for (let r = 0; r <= maxRank; r++) {
      const c = cell(lane.id, r);
      if (!c.length) continue;
      const total = c.reduce((s, n) => s + along(n), 0) + cfg.nodeGap * (c.length - 1);
      // Default: packed and centred in the lane.
      let pos = contentStart + (lane.inner - total) / 2;
      for (const n of c) {
        n.desired = pos + along(n) / 2;
        pos += along(n) + cfg.nodeGap;
      }
      // Pull towards same-lane parents that are already placed.
      for (const n of c) {
        const parents = n.preds.map((p) => nodes.get(p)).filter((p) => p.lane === lane.id && p.rank < r && p.center !== undefined);
        if (parents.length) n.desired = parents.reduce((s, p) => s + p.center, 0) / parents.length;
      }
      const sorted = [...c].sort((x, y) => x.desired - y.desired || x.order - y.order);
      let prev = null;
      for (const n of sorted) {
        n.center = prev ? Math.max(n.desired, prev.center + along(prev) / 2 + cfg.nodeGap + along(n) / 2) : n.desired;
        prev = n;
      }
      const shift = sorted.reduce((s, n) => s + (n.desired - n.center), 0) / sorted.length;
      for (const n of sorted) n.center += shift;
      const lo = sorted[0].center - along(sorted[0]) / 2;
      const last = sorted[sorted.length - 1];
      const hi = last.center + along(last) / 2;
      const fix = lo < contentStart ? contentStart - lo : hi > contentEnd ? contentEnd - hi : 0;
      for (const n of sorted) n.center += fix;
      for (const n of c) {
        const flowPos = rankStart[r] + (rankSize[r] - flow(n)) / 2;
        if (dir === "TB") {
          n.x = n.center - n.w / 2;
          n.y = flowPos;
        } else {
          n.x = flowPos;
          n.y = n.center - n.h / 2;
        }
      }
    }
    a += lane.size + cfg.laneGap;
  }
  const alongEnd = a - cfg.laneGap;

  // Lane frames.
  const frames = lanes
    .filter((l) => l.framed)
    .map((l) =>
      dir === "TB"
        ? { id: l.id, label: l.label, x: l.start, y: flowBegin, w: l.size, h: flowEnd - flowBegin }
        : { id: l.id, label: l.label, x: flowBegin, y: l.start, w: flowEnd - flowBegin, h: l.size },
    );

  // Edges: attach to the sides facing the flow direction.
  const routed = edges.map((e) => {
    const s = nodes.get(e.from);
    const t = nodes.get(e.to);
    const cx = (n) => n.x + n.w / 2;
    const cy = (n) => n.y + n.h / 2;
    let p1, p2;
    if (dir === "TB") {
      if (t.rank > s.rank) [p1, p2] = [[cx(s), s.y + s.h], [cx(t), t.y]];
      else if (t.rank < s.rank) [p1, p2] = [[cx(s), s.y], [cx(t), t.y + t.h]];
      else if (cx(t) >= cx(s)) [p1, p2] = [[s.x + s.w, cy(s)], [t.x, cy(t)]];
      else [p1, p2] = [[s.x, cy(s)], [t.x + t.w, cy(t)]];
    } else {
      if (t.rank > s.rank) [p1, p2] = [[s.x + s.w, cy(s)], [t.x, cy(t)]];
      else if (t.rank < s.rank) [p1, p2] = [[s.x, cy(s)], [t.x + t.w, cy(t)]];
      else if (cy(t) >= cy(s)) [p1, p2] = [[cx(s), s.y + s.h], [cx(t), t.y]];
      else [p1, p2] = [[cx(s), s.y], [cx(t), t.y + t.h]];
    }
    return { ...e, p1, p2 };
  });

  // Canvas.
  const contentRight = dir === "TB" ? alongEnd : flowEnd;
  const contentBottom = dir === "TB" ? flowEnd : alongEnd;
  const titleWidth = Math.max(textWidth(spec.title, 24), spec.subtitle ? textWidth(spec.subtitle, 14) : 0);
  const right = Math.max(contentRight, left + titleWidth);

  // Legend of the roles used, wrapped to the diagram width.
  const legend = [];
  let lx = left;
  let ly = contentBottom + 28;
  for (const r of Object.keys(ROLES).filter((r) => [...nodes.values()].some((n) => n.role === r))) {
    const w = 26 + textWidth(ROLES[r].label, 13);
    if (lx > left && lx + w > right) {
      lx = left;
      ly += 26;
    }
    legend.push({ role: r, x: lx, y: ly });
    lx += w + 22;
  }
  const width = Math.ceil(right + cfg.margin);
  const height = Math.ceil(ly + 18 + cfg.margin);

  return { dir, cfg, nodes: [...nodes.values()], edges: routed, frames, legend, width, height };
}

// ─── SVG ─────────────────────────────────────────────────────────────────────

const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const r1 = (v) => Math.round(v * 10) / 10;

export function renderSvg(spec, lay) {
  const { cfg } = lay;
  const out = [];
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${lay.width}" height="${lay.height}" viewBox="0 0 ${lay.width} ${lay.height}" font-family="${FONT_FAMILY}">`);
  out.push(`<title>${esc(spec.title)}</title>`);
  out.push(`<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="${EDGE_COLOR}"/></marker></defs>`);
  out.push(`<rect width="100%" height="100%" fill="#ffffff"/>`);
  out.push(`<text x="${cfg.margin}" y="${cfg.margin + 22}" font-size="24" font-weight="700" fill="${TEXT_COLOR}">${esc(spec.title)}</text>`);
  if (spec.subtitle) out.push(`<text x="${cfg.margin}" y="${cfg.margin + 46}" font-size="14" fill="#495057">${esc(spec.subtitle)}</text>`);

  for (const fr of lay.frames) {
    out.push(`<rect x="${r1(fr.x)}" y="${r1(fr.y)}" width="${r1(fr.w)}" height="${r1(fr.h)}" rx="12" fill="#f8f9fa" stroke="${FRAME_COLOR}" stroke-width="1.5" stroke-dasharray="8 6"/>`);
    out.push(`<text x="${r1(fr.x + 14)}" y="${r1(fr.y + 21)}" font-size="14" font-weight="700" fill="#495057">${esc(fr.label)}</text>`);
  }
  for (const e of lay.edges) {
    out.push(`<line x1="${r1(e.p1[0])}" y1="${r1(e.p1[1])}" x2="${r1(e.p2[0])}" y2="${r1(e.p2[1])}" stroke="${EDGE_COLOR}" stroke-width="1.8" marker-end="url(#arrow)"/>`);
  }
  for (const n of lay.nodes) {
    const role = ROLES[n.role];
    out.push(`<rect x="${r1(n.x)}" y="${r1(n.y)}" width="${n.w}" height="${n.h}" rx="10" fill="${role.fill}" stroke="${role.stroke}" stroke-width="2"/>`);
    const lh = cfg.fontSize * cfg.lineHeight;
    const firstBaseline = n.y + n.h / 2 - ((n.lines.length - 1) * lh) / 2 + cfg.fontSize * 0.35;
    out.push(`<text x="${r1(n.x + n.w / 2)}" font-size="${cfg.fontSize}" fill="${TEXT_COLOR}" text-anchor="middle">`);
    n.lines.forEach((line, i) => out.push(`<tspan x="${r1(n.x + n.w / 2)}" y="${r1(firstBaseline + i * lh)}">${esc(line)}</tspan>`));
    out.push(`</text>`);
  }
  for (const e of lay.edges.filter((e) => e.label)) {
    const mx = (e.p1[0] + e.p2[0]) / 2;
    const my = (e.p1[1] + e.p2[1]) / 2;
    const w = textWidth(e.label, 13) + 12;
    out.push(`<rect x="${r1(mx - w / 2)}" y="${r1(my - 11)}" width="${r1(w)}" height="22" rx="4" fill="#ffffff" fill-opacity="0.95" stroke="#ced4da" stroke-width="1"/>`);
    out.push(`<text x="${r1(mx)}" y="${r1(my + 4.5)}" font-size="13" fill="${TEXT_COLOR}" text-anchor="middle">${esc(e.label)}</text>`);
  }
  // Legend.
  for (const { role: r, x, y } of lay.legend) {
    out.push(`<rect x="${r1(x)}" y="${r1(y - 7)}" width="16" height="16" rx="3" fill="${ROLES[r].fill}" stroke="${ROLES[r].stroke}" stroke-width="1.5"/>`);
    out.push(`<text x="${r1(x + 22)}" y="${r1(y + 6)}" font-size="13" fill="#495057">${esc(ROLES[r].label)}</text>`);
  }
  out.push(`</svg>`);
  return out.join("\n") + "\n";
}

// ─── Excalidraw ──────────────────────────────────────────────────────────────

function hash(str) {
  let h = 0x811c9dc5;
  for (const ch of str) {
    h ^= ch.codePointAt(0);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h;
}

function base(id, type, x, y, w, h, extra = {}) {
  return {
    id,
    type,
    x: r1(x),
    y: r1(y),
    width: r1(w),
    height: r1(h),
    angle: 0,
    strokeColor: TEXT_COLOR,
    backgroundColor: "transparent",
    fillStyle: "solid",
    strokeWidth: 2,
    strokeStyle: "solid",
    roughness: 0,
    opacity: 100,
    groupIds: [],
    frameId: null,
    roundness: null,
    seed: hash(id) % 2147483647,
    version: 1,
    versionNonce: hash(`${id}#nonce`) % 2147483647,
    isDeleted: false,
    boundElements: null,
    updated: 1,
    link: null,
    locked: false,
    ...extra,
  };
}

function textEl(id, text, x, y, w, h, fontSize, extra = {}) {
  return base(id, "text", x, y, w, h, {
    text,
    originalText: text,
    fontSize,
    fontFamily: 2, // Helvetica
    textAlign: "left",
    verticalAlign: "top",
    containerId: null,
    lineHeight: 1.25,
    autoResize: true,
    ...extra,
  });
}

export function renderExcalidraw(spec, lay) {
  const { cfg } = lay;
  const els = [];
  els.push(textEl("title", spec.title, cfg.margin, cfg.margin, textWidth(spec.title, 24), 30, 24));
  if (spec.subtitle) els.push(textEl("subtitle", spec.subtitle, cfg.margin, cfg.margin + 32, textWidth(spec.subtitle, 14), 18, 14, { strokeColor: "#495057" }));

  for (const fr of lay.frames) {
    els.push(base(`lane-${fr.id}`, "rectangle", fr.x, fr.y, fr.w, fr.h, { strokeColor: FRAME_COLOR, backgroundColor: "#f8f9fa", strokeWidth: 1, strokeStyle: "dashed", roundness: { type: 3 } }));
    els.push(textEl(`lane-${fr.id}-label`, fr.label, fr.x + 14, fr.y + 6, textWidth(fr.label, 14), 18, 14, { strokeColor: "#495057" }));
  }

  const bound = new Map(lay.nodes.map((n) => [n.id, [{ type: "text", id: `node-${n.id}-text` }]]));
  const arrows = [];
  for (const e of lay.edges) {
    const id = `edge-${e.index}-${e.from}-${e.to}`;
    bound.get(e.from).push({ type: "arrow", id });
    bound.get(e.to).push({ type: "arrow", id });
    const [x1, y1] = e.p1;
    const [x2, y2] = e.p2;
    arrows.push(
      base(id, "arrow", x1, y1, Math.abs(x2 - x1), Math.abs(y2 - y1), {
        strokeColor: EDGE_COLOR,
        roundness: { type: 2 },
        boundElements: e.label ? [{ type: "text", id: `${id}-label` }] : null,
        points: [[0, 0], [r1(x2 - x1), r1(y2 - y1)]],
        lastCommittedPoint: null,
        startBinding: { elementId: `node-${e.from}`, focus: 0, gap: 1 },
        endBinding: { elementId: `node-${e.to}`, focus: 0, gap: 1 },
        startArrowhead: null,
        endArrowhead: "arrow",
        elbowed: false,
      }),
    );
    if (e.label) {
      const w = textWidth(e.label, 14);
      arrows.push(textEl(`${id}-label`, e.label, (x1 + x2) / 2 - w / 2, (y1 + y2) / 2 - 9, w, 18, 14, { textAlign: "center", verticalAlign: "middle", containerId: id }));
    }
  }

  for (const n of lay.nodes) {
    const role = ROLES[n.role];
    els.push(base(`node-${n.id}`, "rectangle", n.x, n.y, n.w, n.h, { strokeColor: role.stroke, backgroundColor: role.fill, roundness: { type: 3 }, boundElements: bound.get(n.id) }));
    const text = n.lines.join("\n");
    const th = n.lines.length * cfg.fontSize * 1.25;
    els.push(
      textEl(`node-${n.id}-text`, text, n.x + cfg.padX, n.y + (n.h - th) / 2, n.w - 2 * cfg.padX, th, cfg.fontSize, {
        originalText: n.label,
        textAlign: "center",
        verticalAlign: "middle",
        containerId: `node-${n.id}`,
      }),
    );
  }
  els.push(...arrows);

  for (const { role: r, x, y } of lay.legend) {
    els.push(base(`legend-${r}`, "rectangle", x, y - 7, 16, 16, { strokeColor: ROLES[r].stroke, backgroundColor: ROLES[r].fill, strokeWidth: 1 }));
    els.push(textEl(`legend-${r}-label`, ROLES[r].label, x + 22, y - 8, textWidth(ROLES[r].label, 13), 16, 13, { strokeColor: "#495057" }));
  }

  const doc = {
    type: "excalidraw",
    version: 2,
    source: "saferoute tools/diagrams",
    elements: els,
    appState: { viewBackgroundColor: "#ffffff", gridSize: 20 },
    files: {},
  };
  return JSON.stringify(doc, null, 2) + "\n";
}

// ─── PNG ─────────────────────────────────────────────────────────────────────

async function renderPng(svg) {
  const { Resvg } = await import("@resvg/resvg-js");
  const resvg = new Resvg(svg, {
    fitTo: { mode: "zoom", value: 2 },
    background: "#ffffff",
    font: { loadSystemFonts: true, defaultFontFamily: "Arial" },
  });
  return resvg.render().asPng();
}

// ─── CLI ─────────────────────────────────────────────────────────────────────

async function main(argv) {
  const check = argv.includes("--check");
  let files = argv.filter((a) => !a.startsWith("--")).map((f) => resolve(f));
  if (files.length === 0) {
    files = readdirSync(DEFAULT_SPEC_DIR)
      .filter((f) => /^\d+[-\w]*\.json$/.test(f))
      .sort()
      .map((f) => join(DEFAULT_SPEC_DIR, f));
  }
  if (files.length === 0) throw new Error(`no diagram specs found in ${DEFAULT_SPEC_DIR}`);

  let stale = 0;
  for (const file of files) {
    const spec = JSON.parse(readFileSync(file, "utf8"));
    const warnings = validateSpec(spec, basename(file));
    const lay = layout(spec);
    const stem = file.replace(/\.json$/, "");
    const svg = renderSvg(spec, lay);
    const excalidraw = renderExcalidraw(spec, lay);
    for (const w of warnings) console.warn(`  warning (${basename(file)}): ${w}`);
    if (check) {
      for (const [ext, content] of [[".svg", svg], [".excalidraw", excalidraw]]) {
        const target = stem + ext;
        const current = existsSync(target) ? readFileSync(target, "utf8").replace(/\r\n/g, "\n") : null;
        if (current !== content) {
          console.error(`out of date: ${basename(target)} (run pnpm generate)`);
          stale++;
        }
      }
      continue;
    }
    writeFileSync(`${stem}.svg`, svg);
    writeFileSync(`${stem}.excalidraw`, excalidraw);
    writeFileSync(`${stem}.png`, await renderPng(svg));
    console.log(`generated ${basename(stem)}.{excalidraw,svg,png}  (${lay.nodes.length} nodes, ${lay.edges.length} edges, ${lay.width}x${lay.height})`);
  }
  if (check) {
    if (stale) process.exit(1);
    console.log(`all ${files.length} diagram(s) up to date`);
  }
}

main(process.argv.slice(2)).catch((err) => {
  console.error(`diagram generation failed: ${err.message}`);
  process.exit(1);
});
