# tools/diagrams

Generates diagrams from small JSON specs (Plan v7 §17.7). For each `docs/diagrams/NN-topic.json`
it writes, next to the spec:

- `NN-topic.excalidraw`: editable at [excalidraw.com](https://excalidraw.com) (File → Open)
- `NN-topic.svg`: clean vector export (link this from Notion and PRs)
- `NN-topic.png`: 2× raster export

## Usage

Requires Node ≥ 20 and pnpm. Dependencies are local and pinned; nothing is installed globally.

```sh
cd tools/diagrams
pnpm install                 # once
pnpm generate                # all specs in docs/diagrams/
pnpm generate ../../docs/diagrams/03-sos-device-first.json   # one spec
pnpm check                   # exit 1 if any .excalidraw/.svg is out of date (PNG not checked)
```

Output is deterministic: stable element ids, seeds and text wrapping. Regenerating an unchanged
spec produces no git diff for `.excalidraw` or `.svg`. PNGs can differ by a few bytes between
machines because they depend on the system fonts.

## Spec format

The JSON Schema is in [`diagram.schema.json`](diagram.schema.json). Specs reference it with
`"$schema"`, so editors autocomplete fields.

```json
{
  "$schema": "../../tools/diagrams/diagram.schema.json",
  "title": "Device-first SOS",
  "subtitle": "Plan v7 §7.1",
  "direction": "TB",
  "layout": { "nodeWidth": 210 },
  "groups": [{ "id": "device", "label": "On the phone" }],
  "nodes": [
    { "id": "hold", "label": "Hold 2 s (arm)", "role": "app", "group": "device" },
    { "id": "room", "label": "Room record clientSosId", "role": "data", "group": "device" }
  ],
  "edges": [{ "from": "hold", "to": "room", "label": "after countdown" }]
}
```

| Field | Meaning |
| --- | --- |
| `direction` | `TB` (top to bottom, default) or `LR` (left to right) |
| `nodes[].role` | Colour: `app` blue · `service` green · `data` orange · `security` red (security/emergency) · `infra` purple · `external` grey |
| `nodes[].label` | Wrapped automatically to the node width; `\n` forces a line break |
| `nodes[].group` | Puts the node in a swim lane (dashed frame). Lanes are columns in TB, rows in LR, in `groups` order |
| `nodes[].rank` | Optional layer override. By default a node's layer is the longest path from a source node |
| `edges[].label` | Optional small label at the middle of the arrow |
| `layout` | Optional tuning: `nodeWidth`, `fontSize`, `nodeGap`, `rankGap`, `laneGap` |

Keep diagrams under ~20 nodes (the generator warns above 20). Always check the PNG after
generating. If arrows cross boxes, reorder nodes (order inside a layer follows the spec), reorder
groups, or set `rank`.

## Layout

A layered layout: ranks come from the longest path (or `rank`), nodes in the same lane and rank
are packed side by side and pulled towards their parents, and edges attach to the sides facing the
flow. Edges are straight lines, so long skip-level edges can pass near other boxes. Adjust the spec
if that happens.

## PNG renderer

PNG export uses [`@resvg/resvg-js`](https://github.com/yisibl/resvg-js) (Rust resvg compiled to a
Node native addon). It ships prebuilt binaries for Windows x64, macOS and Linux, so no headless
browser, Python or build tools are needed, and it installs cleanly on Windows. It uses system fonts
(Helvetica/Arial) for text.

## Editing in Excalidraw

Opening a `.excalidraw` file at excalidraw.com gives an editable canvas (boxes, bound labels and
arrows). The JSON spec stays the source of truth. If you change a diagram by hand, copy the change
back into the spec, or the next `pnpm generate` overwrites it.
