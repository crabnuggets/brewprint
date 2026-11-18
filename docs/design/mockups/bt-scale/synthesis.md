# Combined scale flow (S), revision 3

Boards: `s1.png` (pairing to the first pour) and `s2.png` (waits, a lost scale, finishing, the record). Source: `index.html#s1`, `#s2`.

Round 1 averages (two Claude Opus 5.5, one GPT-6.1 Sol): A 7.3, B 5.3, C 5.7 overall. A is the backbone; B's measurements and C's pairing placement and record order are grafted on; B's and C's automatic state models are not. Round 2 scored S 8, 7, 7, each "yes with must-fixes". Round 3 scored revision 2 at 8, 8, 8; revision 3 closes the remaining transition, drop-detection, connection-lifecycle and board-consistency items. Board 6 (scale lost) shows a separate brew from the record on board 9.

## Flow

1. **Pair (C's placement, A's mechanism).** The first time a brew sheet opens with no scale paired, a dismissible card offers "Pair a scale". It opens Android's companion device picker, then asks for Nearby devices (`BLUETOOTH_CONNECT`). "Not now" hides the card for good. Settings → Coffee scale has Add, Forget, and a "Blocked by Android" notice with "Open Android settings" if Nearby devices is refused. No behavior switches. Without the permission, brewing is unchanged.
2. **Connect.** Brewprint connects to the paired scale while it is in the foreground on Brew or a brew sheet, and releases it otherwise, so the Timemore app can still use it. The one exception is a running timer whose service is actually running (rule 10). A paired scale that is off shows a hollow chip and the sheet behaves as without a scale. No background presence service.
3. **Plan (unchanged).** New brew → setup → Use this plan.
4. **Weigh the dose (B, made optional).** The scale card asks for the brewer on the scale, then Tare. Coffee goes in; once steady, "Use 15.2 g" or "Skip". Skip with coffee already steady on the scale tares and arms. Skip with nothing added asks "Add coffee, then Tare", and only that tap arms, so grounds cannot start the timer. "Brew without the scale" runs this brew on the clock only; the pairing stays for next time. Pulse targets never rescale; the plan line keeps "15 g, 1:16, 240 g" and the scale card shows "Weighed 15.2 g".
5. **Tare and arm (A).** After Use (or the Skip path's Tare), the app sends the scale's tare command and waits for about 0 g, so the scale's display matches the cumulative targets. A failed tare leaves manual brewing available. The sheet shows "Waiting for the first pour" with "Start timer now" and "Finish without timing". Arming makes the brew active, so "Keep screen on while brewing" applies from here. Leaving the app or the screen turning off while armed disarms (no timer is running yet, so nothing falls back to the clock); returning re-checks about 0 g and re-arms; if the scale no longer reads about 0 g, the card shows the reading with Tare and waits. "Start timer now" keeps weight guidance; "Brew without the scale" on the dose card is the clock-only choice.
6. **Auto-start.** A sustained rise that takes the reading at least 2 g above the armed zero, with positive flow for about 300 ms, starts the timer (lifting and replacing the brewer does not), backdated to the first rising sample, with "Timer started · Undo" for 5 s.
7. **Pour (A's card, B's instruments).** The accepted focus card stays: state word, "Pour to", "On scale", the bar filling by grams, "12.6 g to go · 3.4 g/s", seconds left, plan strip. Under it, the measured ratio with the ratio the plan will reach ("1:8.7 → 1:15.8"; when the dose was skipped, "1:8.7 → 1:16, planned dose"), and a live water/flow curve with the current and next targets dashed. Next shows one row. Pause, Pour done and Finish brew stay put.
8. **Step rules with a scale.**
   - A pour reaches its target at the first valid reading of at least the target minus 1 g, overshoot included, and stays reached once latched. The card then flips to Wait, and the wait counts down to the next pulse's scheduled start.
   - Reaching the target before the planned pour time ends does not move the schedule; the wait simply runs longer. This keeps weight-ended pours from recording every pulse as early.
   - Short of the target, the card stays on Pour. Past the planned pour time, seconds left reads "+N s", and reaching the target then moves later steps later by N s.
   - Pour done keeps the accepted skip meaning: the pour ends now and later steps move earlier.
   - A zero wait starts the next pour at its scheduled start.
   - Early-start detection arms only after flow has been about 0 for 1 s within the wait, so the tail of the last pour cannot trigger it. Once armed, a rise counts only if it climbs 2 g above the last steady reading. It starts the next pulse through the accepted skip path, recorded as early, with Undo. Pause, Undo and a scale rejoin disarm detection until flow has again been about 0 for 1 s.
   - If the scale is lost during an overrun ("+N s"), the pour ends at that moment and later steps move later by the overrun so far.
   - If any pour is left short of its target, the forgotten-brew stop counts 15 minutes from when flow last stopped, not from a pour end that never came.
9. **Scale lost (A, with B's gap).** No samples for 1 s, the overload flag, a disconnect, or a sudden drop of more than a few grams at any point after the first pour (a pour, a wait, or drawdown before the water reading is kept) all count as lost. That catches the scale's own tare button. A banner appears and the card returns to the accepted clock layout. The curve keeps its gap with "Last reading 151.3 g at 1:45"; a pulse endpoint missed in the gap is "Not recorded". On rejoin, weight resumes only if the first reading fits the last one (no drop beyond a few grams, no more rise than could have been poured); otherwise "Scale reading changed · following the clock" until Finish. A scale that connects after a clock start never takes over that brew. Reconnection never tares.
10. **Screen off.** The scale survives screen-off or leaving the app only while the timer service is actually running: "Live notification" on and notifications allowed by Android (the service's existing eligibility check). Then the service also holds the scale, adding the `connectedDevice` foreground service type only when Nearby devices is granted and a scale is part of this brew, so `startForeground` cannot fail and take the Live notification down with it. Otherwise there is no notification, as `ux.md` promises, and screen off or leaving the app takes the scale-lost path once the timer runs, or disarms before it.
11. **Drawdown (A).** Brew time, then water and ratio beside their plan. Measured total brew water is the steady reading after the last pour, before any drop. If no valid steady reading exists (a lift, tare or gap came first), it is "Not recorded". Finish brew stays a tap and sets the total.
12. **Review (A).** The accepted review plus Dose and Total brew water beside their planned values. Total brew time is the Finish tap, editable; if a dripper lift was seen after the last pour, the row offers "Dripper lifted at 3:24 · Use 3:24". No title, no beverage weight.
13. **Record (C's order, A's content).** Tasting first, then "Timing and water": total brew time, a measured row (dose, water, ratio), the curve against planned targets, and Plan / Started / Reached for every pulse, with Started in the accepted wording ("On schedule", "1:43 12 s early"). The header keeps the plan's dose and ratio.

## Dropped, with reasons

- **B's Quick brew.** A record without a recipe and ticks breaks Repeat, History rows and the glossary's brew record.
- **B's separate live screen and landscape gauge.** They lose the state word, skip and plan strip that the accepted timer relies on, and duplicate the brew UI.
- **B's background presence.** ColorOS battery risk, and holding the scale blocks the Timemore app.
- **C's target rescaling.** Contradicts `ux.md` ("without silently scaling the pulses") and blurs "planned".
- **C's software-only tare.** The scale's display would disagree with every target.
- **C's lift-to-finish and beverage weight.** A drop is indistinguishable from a swirl or the physical tare button; one tare over server and dripper cannot yield beverage mass. The lift survives only as an optional correction in the review.
- **A's three Settings switches.** Scale behaviors are the default; "Brew without the scale" is the per-brew opt-out.

## Changes the docs need

- `ux.md` Scope: equipment integrations are no longer out of scope.
- `ux.md` Saving: "The app does not measure water amounts or pour durations" no longer holds for scale brews; weight-ended pours can make later pulses late as well as early.
- `ux.md` Focus card: Next shows one row while a scale is connected, to make room for the curve.
- `ux.md` Timer settings: the Live notification service also holds the scale; with it off, scale brews need the screen on.
- `GLOSSARY.md`: "Measured dose" and measured total brew water, distinct from the planned values. Total brew time ends at Finish brew unless corrected in the review.
- `ux.md` Brew timer: reaching a target early lengthens the wait; overrunning moves later steps later; Pour done still moves them earlier.

## Unverified on hardware

Weight resolution; whether the Dot streams weight with its own timer stopped; bonding behavior; overload limits; whether the Dot's timer auto-starts on weight; detection thresholds (2 g / 300 ms, flow-stop 1 s, drop-to-zero).

## Owner decisions (2026-11-18)

1. The TIMEMORE app is still used, so Brewprint releases the scale outside a brew.
2. A weighed dose rescales the brew's pulses to keep the recipe's ratio (rule 4 above is superseded; the plan line shows the weighed dose).
3. Swirling, yes; taring mid-brew, no. A drop pauses weight guidance until the reading returns, rather than losing the scale.
4. The phone is propped on the counter, so a "Sound at each step" switch is added.
5. A plain ongoing notification is acceptable, so a scale brew keeps the scale with the screen off even with Live notification off.

`docs/ux.md` "Brewing with a scale" is now the source of truth.

## Open questions for the owner (answered above)

1. Do you still use the Timemore app with this scale? (Decides whether Brewprint must release the connection outside a brew, as S does.)
2. Should a weighed dose ever rescale the pour targets, with your confirmation? S says never.
3. Do you lift or swirl the dripper, or press the scale's tare button, during a brew?
4. Is the phone propped on the counter while brewing? If so, vibration won't be felt and sound cues would serve better.
5. With Live notification off, is a screen-on requirement for scale brews acceptable, or would you accept a plain ongoing notification so the scale survives screen-off?
