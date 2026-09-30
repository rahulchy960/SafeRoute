# docs/diagrams/

Architecture and flow diagrams (Plan v7 §17.7). Each diagram has four files:

| File | What it is |
| --- | --- |
| `NN-topic.json` | **Source of truth.** Small JSON description (nodes, edges, groups). |
| `NN-topic.excalidraw` | Generated. Open and edit at [excalidraw.com](https://excalidraw.com) (File → Open). |
| `NN-topic.svg` | Generated. Link this from Notion and PRs. |
| `NN-topic.png` | Generated. For places that cannot show SVG. |

To regenerate, edit the JSON and run the generator (see `tools/diagrams/README.md`):

```sh
cd tools/diagrams && pnpm install && pnpm generate
```

If you change a diagram by hand in Excalidraw, update the JSON too. Otherwise the next
regeneration overwrites your change.

## Starter diagrams (P001)

- `01-prompt-workflow`: lifecycle of one prompt, from the Opus chat to a merge on GitHub
- `02-mvp-architecture`: MVP topology (Plan v7 §4, §13)
- `03-sos-device-first`: device-first SOS trigger flow (Plan v7 §7.1)
- `04-scaling-stages`: scaling stages 0–3 (Plan v7 §14.2)

Later prompts add diagrams named `NNN-topic`, where `NNN` is the prompt number.

**Colours by role:** blue = app/UI · green = services · orange = data ·
red = security/emergency · purple = infra · grey = external.
