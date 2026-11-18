# Council brief: Bluetooth scale brewing flows

You are one of three independent reviewers. Work read-only: do not edit, create, or delete any file, and do not commit.

## The product

Brewprint is a personal, offline Android app (Kotlin, Compose) for filter coffee. It records brews (beans, grinder and tick setting, recipe with pulses, observations) and has an accepted guided brew timer. Read these before judging:

- `docs/ux.md` — journeys, including "Brew sheet and timing", "Brew timer", and "Saving and reviewing a brew".
- `docs/ui-design.md` — the owner's visual direction (crisp, monochrome, spacious, selective glass, little subtext).
- `GLOSSARY.md` — domain terms (pulse, step, brew time, total brew water, drawdown...).
- `docs/design/mockups/brew-timer/01-running.png`, `02-pause-plan-drawdown-finish.png`, `03-recipes-forgotten-record.png` — the accepted timer without a scale.

## The request

The owner wants optional integration with a TIMEMORE Dot ("Black Mirror") Bluetooth scale. They like these things from Timemore's own app: live total water, flow rate, progress toward a target ratio, a live water/flow chart, the timer starting when pouring begins (no Start button), and automatic connection once the scale is on. They dislike Timemore requiring a typed brew title, and want overall UI/UX more polished. Redesigning existing flows is allowed if the result flows better.

## What the scale can do (open protocol v1.0.3)

- GATT service 0xFFF0; notify 0xFFF1, write-without-response 0xFFF2. Frames `A5 5A | opcode | cmd | len(2,BE) | data | CRC16`.
- Pushes weight (int32), flow rate (int16, 0.1 g/s units), the scale's own timer (uint16 s), and an overload flag every 100 ms "while active".
- Commands: tare, timer start/pause/reset, weight unit, battery read/notify, device name read/write, power off, forget device.
- Not documented: physical button events, whether the scale auto-starts its timer, bonding/pairing requirements, advertising layout, weight resolution. No drawdown or beverage signal exists; anything like "dripper lifted" must be inferred from weight.

## The three proposals

Boards are PNGs (open them and look closely); `index.html` is their source.

- **A, Scale on the brew sheet** — `docs/design/mockups/bt-scale/a1.png`, `a2.png`. Minimal change. Pair once in Settings; connect only while a brew sheet is open. The accepted focus card gains an "On scale" reading; pours end by weight, waits stay on the clock; armed "Waiting for the first pour" state; falls back to the clock timer if the scale drops; the record's Timing section gains a curve and grams reached per pulse.
- **B, Live brew instrument** — `b1.png`, `b2.png`. Timemore-inspired. Live scale card on Brew home with Companion Device presence (connects when the scale powers on); Quick brew path without a recipe; dose weighed on the scale; dedicated live screen (huge weight, flow, ratio bar with target, live water/flow chart); landscape ratio gauge; Brew summary with stat tiles and curve; record leads with the curve.
- **C, Hands-free brew** — `c1.png`, `c2.png`. Scale drives the brew. Offered inline the first time a brew sheet opens near a powered scale; stages Brewer → Coffee → Pour with software tare and automatic dose capture (targets rescale to keep the ratio); one ring per pulse fills to its target with haptics; waits as a draining ring, water landing early starts the next pulse; lifting the dripper finishes the brew (Undo 5 s) and the cup left on the scale gives beverage weight; review shows tiles and grams over target per pulse.

## What to return

1. A score table: rows are the aspects below, columns A, B, C, each an integer out of 10. Add a one-line reason per cell or per row.
   1. Connection and pairing (first time and every day)
   2. Pre-brew preparation (tare, dose)
   3. Starting the brew (auto-start on pour)
   4. Live data at arm's length (weight, flow, ratio, curve)
   5. Pulse guidance with a recipe
   6. Drawdown and finishing
   7. Review and save (friction, no typed title)
   8. Saved record and History value
   9. Failure handling and the no-scale path
   10. Fit with Brewprint's docs and visual direction
   11. Feasibility and risk against the protocol and Android BLE
   12. Overall
2. For each proposal: its best ideas worth keeping and its weakest ideas to drop, with reasons.
3. Combination hazards: pairs of ideas from different proposals that should not be combined, and why.
4. Your recommended flow, as numbered steps from opening the app to the saved record, naming which proposal each step comes from or what you changed. Keep scope to what this feature needs.
5. Open questions only the owner can answer, at most five.

Be concrete and critical. Judge flows and behavior first, visuals second. Call out anything that contradicts `docs/ux.md` or the glossary, or that the protocol cannot support.
