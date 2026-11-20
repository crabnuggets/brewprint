# Filter coffee app UX and user journeys

This is a proposed first version for a personal Android app. Its main job is to help you remember what you brewed, notice what you enjoy in coffee, and repeat a brew with a small adjustment. Product choices below are draft assumptions for review, not implementation commitments.

Use the terms in [the glossary](../GLOSSARY.md). On forms, use familiar labels such as "Coffee dose, g", "Grind setting, ticks", and "Variety / cultivar". Keep "ticks" as the user's unit; "clicks" is another common label for a stepped grinder's adjustments.

See [the UI design direction](ui-design.md) for the owner's visual preferences and grinder dial interaction concept.

## Scope and defaults

The first version stores beans, grinders, recipes, and brew records on the device. It works offline, with no account or sign-in. An optional Bluetooth coffee scale can guide a brew (see [Brewing with a scale](#brewing-with-a-scale)); other equipment integrations, cloud sync, sharing, inventory quantities, and analytics are outside this draft's scope. Local storage means uninstalling the app or losing the device can lose the records; export and backup remain a later product decision.

The app remembers the default grinder, each grinder's last recorded tick setting, and the most recently used beans and recipe. It shows these choices before each brew so the user can correct them. The app cannot know the physical grinder's current setting; the UI calls it "Last recorded setting".

Recipes describe the brewing plan. Beans, grinder, and tick setting belong to an individual brew record, so the same recipe can be used with another coffee or grinder.

## Navigation

Use three bottom navigation destinations:

- Brew: the everyday starting point, with "New brew", the last recorded grinder setting, and the most recent brew's "Repeat" action.
- History: completed brew records, newest first. Open a record to inspect, edit, or repeat it.
- Library: recipes, beans, and grinders, grouped into three plainly labeled lists.

Create and edit screens open above these destinations. Android Back returns to the previous screen. A brew setup with nothing chosen, or a new one left as it opened from "New brew" or "Repeat", is discarded on Back, so it neither blocks "Repeat" nor keeps a stale start time. Unsaved form edits prompt "Discard changes?" with "Keep editing" and "Discard". An active brew draft saves automatically and offers "Resume brew" on the Brew screen after navigation or an app restart. Support one active draft at a time for this version.

## First brew journey

1. Open the app to an empty Brew screen with "Record your first brew" and a short explanation of what will be saved. Skip a separate onboarding slideshow.
2. Tap "New brew" to open setup. Required rows show "Add beans", "Add recipe", and "Add grinder". Each opens its create form directly.
3. Add a grinder by name, enter its current tick setting, and optionally describe the reference point used for counting ticks. The first grinder becomes the default.
4. Add beans with a name and roaster. The user may fill in origin, variety, and expected tasting notes now or later.
5. Create a recipe with a name, dose, ratio, and pulses. A running total helps the user make the pulses match the target water amount.
6. Return to setup with the new items selected. Review the tick setting and recipe summary, then tap "Use this plan".
7. Read the brew sheet while preparing the coffee. It shows the chosen beans, grinder setting, dose, ratio, and ordered pulse instructions. Tap "Start timer" at the first pour to be guided through the pulses, or "Finish without timing".
8. Tap "Finish brew", optionally add observations, then tap "Save brew". The app opens the saved record and makes it available in History.

Creating a library item during setup returns directly to that setup without losing other choices. When there is nothing to choose from, a setup row opens the create form directly. Save is available once required fields are valid; optional metadata never blocks a brew.

## Everyday journey

Open Brew, tap "New brew", and review the remembered beans, recipe, grinder, and tick setting. Change only what differs today. Tap "Use this plan", brew from the sheet, and save with optional observations.

For a small grind adjustment, change "Grind setting, ticks" in setup. Saving the brew records that value and updates that grinder's last recorded setting. Discarding the draft does neither. The grinder detail screen also has an explicit "Update last recorded setting" action for adjustments made without logging a brew.

"Repeat" on a historical brew copies its saved beans, grinder, tick setting, and recipe plan into a new draft. It shows the old setting even if the grinder's current recorded setting differs. Setup displays both values in that case: the brew's setting, for example 24, with "Last recorded: 26 ticks" and "Use 26 ticks" beneath it. The user chooses the setting before continuing. Repeating never changes the original record.

## Library forms

### Beans

Require a name and roaster. Keep these as text fields rather than separate roaster records. Example name: "Ethiopia natural".

Offer optional origin, variety / cultivar, processing method, expected tasting notes, and general notes. Origin and variety accept free text so blends and incomplete bag labels do not need special workflows. Prompt "Country, region, or farm if known" for origin. Expected notes might read "Blueberry, cocoa"; the user's experience goes into brew observations instead.

Allow edits and archiving. Archived beans disappear from normal selection but remain readable in History. A repeat using archived beans identifies them and offers "Use anyway" or "Choose other beans".

### Grinders

Require a name. Offer a last recorded setting in whole ticks from 0 through 100, inclusive, and optional reference notes, such as "Count from my usual zero mark". Keep the range visible as neutral helper text. Do not assume a shared physical zero or direction of adjustment across grinders. Do not instruct the user to force the burrs closed to establish a reference. The half-wheel dial and direct numeric entry use the same range. The dial has no visible increase or decrease buttons; retain accessible wheel adjustment semantics.

With one grinder, select it automatically. With several, let the user choose a default in the grinder list. Selecting another grinder loads its own recorded setting, never the previous grinder's tick count. Require a setting in brew setup if the selected grinder has none.

Allow renaming and archiving. Archiving the default requires selecting another active grinder, or clears the default if none remain. Old brew records keep their original grinder information. Repeating an archived grinder requires choosing an active grinder before continuing.

### Recipes

Require a name, positive coffee dose in grams, positive ratio denominator, and at least one pulse. Allow optional recipe notes for preparation instructions. Dose and water inputs accept up to one decimal place; durations use whole seconds. Show units beside every numeric field.

Display the ratio as "1 : [16]", with helper text "1 g coffee to 16 g water". Calculate total brew water as dose multiplied by ratio denominator, rounded to 0.1 g. This field is read-only. For example, 15 g at 1:16 gives 240 g total brew water. This is water added, not the beverage collected in the cup.

Each pulse editor contains:

- Optional label, such as "Bloom". Otherwise show "Pulse 1", "Pulse 2", and so on.
- Water to add in grams, required and greater than zero.
- Pour duration in seconds, required and greater than zero.
- Wait after pouring in seconds, zero or greater, for every pulse except the last.

Show both the amount to add and the cumulative scale reading. For example, "Add 90 g • Pour to 150 g total". Cumulative readings assume the scale is tared before the first pour and stays untared between pulses.

"Add pulse" copies the previous pulse's water amount and pour duration into a separate editable pulse. Copy its wait when one exists; otherwise prefill zero. Labels are not copied. When the previous last pulse gains a following pulse, show its wait field with zero prefilled. The new last pulse has no wait field. Explain this behavior beside the add action so a needed wait is not overlooked.

Allow removing pulses and dragging them by a clearly labeled handle to rearrange them. Keep labeled move up and move down actions available for accessibility. Recalculate cumulative water and timing after every change. Waits belong to the pulse they follow. If a pulse becomes last, remove its wait and announce that change; if it later gains a successor, its wait starts at zero. A recipe must retain at least one pulse.

Below the list, show "Planned pours: 240 g / Target: 240 g". If the rounded pulse sum differs from the target, show the difference, such as "Add 20 g across your pulses to reach 240 g". Keep the edits on screen but block saving until they match. Changing dose or ratio recalculates the target without silently scaling the pulses; explain the resulting mismatch beside the total.

Allow editing, duplicating, and archiving recipes. On a Library item's screen these sit in the header, as "Edit" and a "More actions" menu, so a long recipe never hides them. A brew-specific recipe adjustment is outside the first version: duplicate or edit the recipe before using it. Existing brew records retain the recipe as it was at the time.

## Brew sheet and timing

The brew sheet presents a static schedule with elapsed start times, per-pulse amounts, cumulative amounts, pour durations, and waits. Start time zero means the start of the first pour. Each following start time equals the previous start plus its pour duration plus its wait.

An illustrative 15 g, 1:16 recipe has three pulses. Pour 60 g over 10 seconds, then wait 30 seconds. At 0:40, pour another 90 g over 20 seconds, then wait 20 seconds. At 1:20, pour the final 90 g over 20 seconds. The cumulative amounts are 60 g, 150 g, and 240 g. Scheduled pouring ends at 1:40.

These numbers demonstrate the UI and calculations; they are not a recommended recipe. Final drawdown can continue after the scheduled pours. The app does not treat the end of the last pour as the completion of the brew. The user decides when to tap "Finish brew".

### Brew timer

The brew sheet offers "Start timer" and "Finish without timing". The timer is optional; an untimed brew works as before. See the [brew timer mockups](design/mockups/brew-timer/index.html) for appearance.

- **Steps.** A step is a pour or a wait. The timer moves through the recipe's steps on schedule. A zero wait starts the next pour immediately. After the last pour the sheet shows "Drawdown" and brew time keeps counting until "Finish brew".
- **Focus card.** It leads with the state word, "Pour", "Wait", "Paused" or "Drawdown", then the cumulative scale reading to pour to and the seconds left. A plan strip shows the whole recipe, and "Next" lists up to two upcoming rows (one while a scale guides the brew, to make room for its curve). "N done, show plan" opens the full plan, which stays open until "Back to current step", the Back arrow, or Android Back, each of which returns to the current step.
- **Skip.** The skip button is labelled by its effect, in two or three words so it fits a phone: "Pour done" during a pour, "Start pulse N" during a wait. Skipping moves every later step earlier and keeps their durations. The button ignores taps for 1 second after a step changes. "Undo" is offered for 5 seconds and restores the schedule as it was, even if a step has changed on its own in that time.
- **Pause.** "Pause" holds the schedule while brew time keeps counting. "Resume" continues the held step; "Start pulse N" ends the pause and starts the next pour. Pause is not offered during drawdown.
- **Finish.** "Finish brew" stops the clock and opens the review with "Total brew time", which can be corrected. Back from the review restores the timer as it was, including a pause.
- **A forgotten brew.** The timer stops itself 15 minutes after the last pour ends, or 15 minutes after a pause began. The Brew screen then shows when it stopped, and "Resume brew" opens the review. The total is "Not recorded" until the user enters one.
- **Closing the app.** The timer is kept as timestamps in the draft, so reopening the app shows the correct step and brew time.

### Timer settings and live notification

Settings holds the appearance choice and four brew timer switches: "Live notification" (off by default), "Vibrate at each step", "Sound at each step", and "Keep screen on while brewing" (all three on by default). The sound is a short tone at each step change, for a phone propped on the counter where a vibration goes unfelt.

Turning "Live notification" on is the only moment the Android notification permission prompt appears. If notifications are refused or turned off, a "Blocked by Android" notice appears under the switch with "Open Android settings". The in-app timer works the same either way.

With the switch on, a running timer shows one ongoing notification, requested as an Android 16 Live Update with the standard progress template. Each part has one job:

- **Icon.** It shows the state: a drop for a pour, an hourglass for a wait, a pause mark, or a stopwatch during drawdown.
- **Status-bar chip.** Beside the icon it shows only the numbers to act on: the scale target and seconds left during a pour ("145g 7s"), seconds left during a wait, "Paused", or brew time during drawdown. It is kept short because ColorOS sizes the capsule to it.
- **Title and text.** The title is the instruction in a few words, such as "Pour to 145 g" or "Wait, then 195 g". The text line gives the pulse.
- **Header clock and bar.** The system countdown shows the time left in the step, or brew time during drawdown. The bar has one segment per pulse and moves once a second. While the screen is off, the notification is redrawn only when the step or state changes.
- **Actions.** Tapping the notification opens the sheet, so there are only two short actions: "Pause" or "Resume", and the skip action, which becomes "Undo" for 5 seconds after a skip. During drawdown the action is "Finish".

The target device is ColorOS 16, which shows the Live Update as a capsule in the status bar.

## Brewing with a scale

A TIMEMORE Dot (the "Black Mirror" open protocol) can guide a timed brew. The scale is optional: without one, or with "Brew without the scale", every screen works as described above. See the [scale mockups](design/mockups/bt-scale/index.html#s1) for appearance.

### Pairing and connection

- **Pairing.** The first time a brew sheet opens with no scale paired, a card offers "Brew with a scale?" with "Pair a scale" and "Not now", and says a scale can be paired later in Settings. If Android finds no compatible scale, the app says "No compatible scale found. Turn it on and try again."; any other failure says "Couldn't pair the scale. Try again."; declining or cancelling the picker says nothing. "Not now" hides the card for good. "Pair a scale" opens Android's companion device picker, listing only nearby devices named like a TIMEMORE scale that speaks the open protocol (the Dot and the Basic3 Link), then asks for Nearby devices once. Settings → "Coffee scale" shows the paired scale with "Forget", or "Add a scale". If Nearby devices is refused, a "Blocked by Android" notice appears there with "Open Android settings", and brewing works without the scale.
- **Connection.** Brewprint connects to the paired scale whenever the app is open, so a scale switched on beforehand is ready. It releases the scale when the app leaves the screen and no scale brew is running, so the TIMEMORE app can still use it. A scale brew keeps the connection with the screen off or the app in the background: the brew timer's service holds it, showing the live notification when that switch is on, or a plain ongoing "Brewing with <scale>" notification when it is off.

### Before the first pour

With a paired scale connected, the brew sheet shows a scale card above the plan:

1. **Brewer.** "Put the brewer on the scale" with the live reading and "Tare".
2. **Coffee.** After the tare, the card shows only "Taring the brewer" until the scale reads about 0 g, then asks for the coffee and shows its reading, labelled "Dose ready" (and announced) once it holds steady. Choosing other beans or another recipe after the scale steps began starts them again, with the recipe's own dose. If the scale disconnects before a dose is chosen, even with the sheet closed, the card returns to "Put the brewer on the scale" with "Tare the brewer again. Take the coffee out first if you've added it." When the reading differs from the plan, a line says that using it adjusts the pours to keep the ratio. Once it holds steady above about 0 g (1 g), it offers "Use 15.2 g" and "Keep 15 g". "Use" records the weighed dose and rescales this brew's pulses to keep the recipe's ratio: 15.2 g at 1:16 becomes 243.2 g, each pulse scaled and rounded to 0.1 g, the last pulse taking the rounding. The Library recipe does not change. "Keep" keeps the recipe's dose. Either way the app then tares the scale, so the scale's own display matches the cumulative targets. If the scale does not reach about 0 g within 1.5 seconds of a tare, the card leads with "The scale didn't tare", says "Tap Tare again, or press the scale's tare button.", and offers "Tare"; the sheet does not arm until the scale reads about 0 g.
3. **Armed.** Once the scale has held steady at about 0 g since its last tare, the card reads "On scale, tared · Weighed 15.2 g" (without the weighed part after "Keep") and the footer shows "Waiting for the first pour", with "Start timer now" and "Finish without timing". Before that, the footer offers only "Finish without timing" and shows "Waiting for the scale to read 0 g" while a tare is pending, "Waiting for the scale to settle" while the reading is near 0 g but not yet steady, or "Tare the scale to 0 g" with a filled "Tare" on the card when it is off zero, and no status while the card shows a failed tare; the card adds "Weighed 15.2 g" only once tared; "Brew without the scale" is the way to a clock-only start. If readings stop, the scale overloads, or the link drops after the scale steps began, before the first pour, the card says "No scale reading" with "Check that the scale is on, nearby, and not overloaded. Its reading comes back here." and shows no weight or footer status until a reading returns. The screen stays on from here. Leaving the app disarms; on return, the card shows the reading and asks for "Tare" if it is no longer about 0 g.

"Brew without the scale" on the card makes this brew clock-only and keeps the pairing. While the brewer and coffee are weighed, the footer offers only "Finish without timing", so the card's own step leads; "Brew without the scale" is the way to a clock-only start.

### During the brew

- **Start.** A rise of at least 2 g above the tared zero, with flow sustained for about 300 ms, starts the timer, backdated to the first rising sample. "Timer started · Undo" shows for 5 seconds. Lifting and replacing the brewer does not start it.
- **Focus card.** During a pour, "Pour to" sits beside "On scale", the live reading. The bar fills by grams, and the detail line reads "12.6 g to go · 3.4 g/s" with the seconds left, or "+3 s" once the planned pour time has passed. Under the plan strip, a card shows the ratio so far and where the plan ends ("Ratio 1:8.7 → 1:16"), with a live water and flow curve. The target to pour to now (during a wait, the next pour's) is a darker dashed line and the total brew water a faint dotted line, each labelled with its grams at the right end.
- **Pour end.** A pour reaches its target at the first reading of at least the target minus 1 g, overshoot included, and stays reached. The card then shows "Wait", counting to the next pulse's scheduled start. Reaching the target before the planned pour time ends lengthens the wait and leaves the schedule alone. Short of the target, the card stays on "Pour"; once the scale reaches the target after the planned pour time, later steps move later by the overrun. "Pour done" keeps its meaning: the pour ends now and later steps move earlier. A pour held short of its target does not delay the forgotten-brew stop.
- **Early pulse.** During a wait, once flow has been about 0 for 1 second, a new rise of at least 2 g above that steady reading starts the next pulse through the same path as "Start pulse N", recorded as early, with Undo. Pause, Undo, and the scale returning disarm this until flow has again been about 0 for 1 second.
- **Lifting the dripper.** A drop of more than 5 g below the last steady reading pauses weight guidance, for example while swirling, and steps follow the clock. When the reading returns to within 3 g of the steady reading, guidance resumes.
- **Scale lost.** No reading for 1 second, the overload flag, or a disconnect shows "Scale disconnected · Following the clock until it is back." The card returns to the clock layout in the same positions, and a pour past its planned time ends then, moving later steps later by the overrun so far. The curve keeps a gap. On reconnecting, guidance resumes only if the reading has not dropped below the last steady reading; otherwise the brew follows the clock until "Finish brew". A scale that connects after a clock-only start never takes over that brew. Reconnecting never tares.
- **Drawdown.** The card adds "Water", the steady reading once flow stops after the last pour, and the ratio, each beside its plan. Lifting the dripper cannot change it. If the dripper came off before a steady reading, water is "Not recorded" and the lift time is still offered in the review.

### Finishing

"Finish brew" stays a tap and sets the total brew time. In the review's results card, "Dose" is marked "weighed" when measured, and "Water" and "Ratio" show the measured values with the planned ones beneath. If the dripper was lifted after the last pour, the card offers "Dripper lifted at 3:24 · Use 3:24" under the total. There is no beverage weight.

The live notification is unchanged during a scale brew: its chip still shows the target and seconds left.

## Saving and reviewing a brew

"Finish brew" opens a short review with optional brew observations. In order:

1. The beans as the title, with the recipe and tick setting beneath, and "Change setup" to go back and correct setup before saving.
2. The brew date and time, prefilled when the draft starts, with "Change date" to log an earlier brew.
3. A results card. A timed brew shows "Total brew time" with "Change total"; a scale brew adds the measured "Water", "Ratio", and "Dose". Without a scale, the card shows the planned dose, water, and ratio, labelled as planned. An untimed brew without a scale has no results card.
4. "How was this cup?"

The three "Change …" actions are icon buttons with those names.

"Discard brew", a red trash icon in the header of setup and of the review, asks "Discard this brew?" with "Keep brew" and "Discard". Discarding removes the draft and saves nothing. The running sheet has no discard: "Finish brew", then "Discard brew".

A completed record stores the recipe plan, beans' details, grinder name, and tick setting as they were used, along with date/time and observations. Later library edits do not rewrite history. Planned amounts and durations remain labeled as planned. A timed brew also stores its total brew time, the time spent paused, and each pulse's actual start, shown in a "Timing" section beside the planned starts. Each pulse takes one line: its name, planned start, and how its actual start differed, as "On time", "+9 s" (late), or "−12 s" (early). Screen readers hear "9 seconds late" and "12 seconds early". An untimed brew has no Timing section. Without a scale, the app does not measure water amounts or pour durations.

A scale brew's record also shows, in "Timing and water", a measured row (dose, water, ratio), the water curve over a faint line of the plan (rising during each planned pour, flat during each wait) and the total brew water, and a "Reached" column with the steady reading after each pulse, or "—" when the scale gave no steady reading before the next pulse's water began. A note under the table says what "—" means. If the next pulse begins before the reading settles, after a late pour's short wait or "Start pulse N", a steady reading still counts until the reading rises 2 g above where that pulse began, or above the last pulse's target if its pour stopped up to 1 g short of it. Water stays on the scale, so a steady reading seen after a gap, within the same wait, still counts. The tasting journal stays first on the record.

History rows show date, beans, recipe, and tick setting. The detail screen includes the grinder name and full saved plan. "Edit brew" permits explicit corrections to the record without editing library items or changing the grinder's current recorded setting. "Delete brew" removes only that record and offers "Undo" for 5 seconds, which restores it. In History, swiping a row left deletes it the same way and swiping right repeats it. Library items use archive rather than deletion in this version.

If saving fails, retain the draft and show "Couldn't save brew. Try again." Do not show a completed record or update remembered values until saving succeeds. Repeated taps on Save must create only one record.

## Tasting journal for each brew

Every brew has its own optional tasting journal within brew observations. Recipe notes describe preparation; bean notes describe the coffee and the roaster's expectations; the journal describes what the user actually noticed in this cup. Repeating a brew starts with an empty journal rather than copying previous impressions.

### Quick entry

After "Finish brew", show "How was this cup?" above a multiline text field with the prompt "What did you notice? Everyday words are welcome." Keep this field visible without expanding anything. It accepts tasting impressions, brewing mishaps, or any other notes.

Below it, offer "Explore the taste" for optional guided fields and "Next time" for a short reminder such as "Try one tick finer". All fields start empty. Save remains available without notes, with helper text "You can taste first and add notes later." Avoid a mandatory rating or a completion percentage.

The saved brew detail shows "Add tasting notes" when empty and "Edit tasting notes" when populated. The most recent brew on the Brew screen exposes the same action, making it easy to return while drinking. Editing notes on a saved brew has an explicit "Save notes" action. Retain unsaved note edits locally across interruption and offer to resume them; discarding those edits preserves the last saved notes.

### Guided exploration

"Explore the taste" expands a short set of optional prompts. Each prompt has a plain-language explanation available beside its label. These are personal descriptions, not a professional scoring form.

- Aroma: "What do you smell?" Offer a text field with examples such as floral, toasted nuts, or fruit.
- Flavor: "What does it remind you of?" Offer a few selectable suggestions such as fruity, floral, chocolatey, nutty, and spicy, plus a text field for the user's own words. Multiple suggestions may be selected and tapped again to remove. Broad descriptions are enough; a specific fruit is never required.
- Sweetness: "How much sweetness do you notice?" Offer Low, Medium, and High, with optional descriptive text.
- Acidity: "How bright or tangy does it taste?" Offer Low, Medium, and High, with optional descriptive text. Explain that this records the sensation, not a measured pH or a judgment of quality.
- Bitterness: "How much bitterness do you notice?" Offer Low, Medium, and High, with optional descriptive text.
- Body: "How light or weighty does it feel in your mouth?" Offer Light, Medium, and Full.
- Mouthfeel: "What is the texture like?" Offer selectable suggestions such as silky, creamy, syrupy, and drying, plus free text. Keep this distinct from body's sense of weight.
- Finish / aftertaste: "What lingers after swallowing?" Offer a text field and an optional Short, Medium, or Long selection.

Every single-choice group starts unselected and includes a clear action. Blank means unrecorded, not low or absent. Suggestions are examples, not inferred answers. Show selected states in text and to screen readers, and allow chip rows to wrap at larger text sizes.

Keep the roaster's expected notes behind "View roaster's notes" during entry, so the user's own impressions come first. Opening them shows a separately labeled reference and never fills the journal. Include a brief reminder: "Your notes don't need to match the bag."

### Notice changes and revisit

Offer an optional text field labeled "As it cools" with the prompt "Did anything change as you kept drinking?" Keep one editable journal per brew in this version; separate timed tasting entries and temperature measurements are outside scope.

On the saved record, show the user's written impression first, followed by only the guided fields they filled in and their "Next time" reminder. Keep preparation details below the journal. History rows may show a one-line excerpt of the written impression; an empty journal displays "Add tasting notes" without a warning or an incomplete status.

For example, the user saves a brew before tasting, returns from the Brew screen, writes "Smells like cocoa; lighter than I expected", selects Light body and silky mouthfeel, and adds "Sweeter as it cooled" later. On their next visit to that record, those observations sit beside the exact recipe and tick setting that produced the cup. The app makes no automatic diagnosis or grinder adjustment from tasting notes.

## Android interaction details

Use numeric keyboards for quantities and durations, readable unit labels, and inline errors next to the relevant fields. Show neutral required indicators before interaction. Show field errors after meaningful editing or after the user leaves the field, and keep untouched blank fields neutral. Required validation still gates saving. Use softly filled rounded fields with subtle borders consistently in library forms, dates, and the tasting journal. Keep primary actions reachable above the keyboard. Support large text and screen readers; read cumulative water with its meaning rather than as an unlabeled number. Use text as well as color for errors and selected defaults. Pulse reordering supports dragging and labeled move actions, so dragging is never required.

## Review scenarios

- A first-time user creates all three library items from brew setup and returns with their choices intact.
- A returning user records a brew without retyping beans, recipe, or grinder.
- A user changes a dose and sees the new water target and exact pulse mismatch before saving.
- A copied pulse can be edited without changing the preceding pulse.
- A zero wait starts the next pour immediately; the last pulse never requires an inter-pulse wait.
- Switching grinders restores the selected grinder's own tick setting.
- Editing a recipe or bean description leaves completed records unchanged.
- Repeating an older brew makes a differing historical grind setting visible.
- Leaving an active draft and reopening the app offers the same draft for resumption.
- A failed save preserves the user's notes and does not create a duplicate record.
- A user saves without tasting notes and adds them later from the most recent brew or History.
- A user records only free text, only guided observations, or both without completing unrelated fields.
- Body and mouthfeel can be recorded separately, and a selected intensity can be cleared back to unrecorded.
- Viewing expected tasting notes does not populate or overwrite the user's journal.
- Repeating a brew leaves its new journal empty and preserves the original journal.
- Interrupted note edits can be resumed without losing the existing saved brew.
- A timed brew left untouched records every pulse on schedule.
- Skipping a wait 12 seconds early records that pulse 12 seconds early and moves later pulses earlier; Undo puts the schedule back.
- A 15-second pause delays later pulses by 15 seconds and adds 15 seconds to the total.
- A brew left alone stops after 15 minutes and saves with no total unless one is entered.
- With "Live notification" off and no scale brew, no notification or permission prompt appears.
- Weighing 15.2 g of coffee for a 15 g, 1:16 recipe rescales this brew to 243.2 g and leaves the Library recipe at 15 g.
- Pouring from the armed sheet starts the timer at the first pour; lifting and replacing the brewer does not.
- A pour that reaches its target early lengthens the wait; one that overruns moves later pulses later.
- A pour that overruns into a short wait still records Reached if the reading settles before the next pulse's water lands.
- Swirling the dripper during a wait neither starts the next pulse nor loses the scale.
- Switching the scale off mid-brew follows the clock and keeps a gap in the saved curve.
- A ten-pulse record at the default text size shows each pulse's timing on one line on a 360 dp wide phone.
- Swiping a brew away in History and tapping Undo restores it unchanged.

## Assumptions to revisit

This draft assumes whole ticks, optional bean metadata beyond name and roaster, and recipes with absolute water amounts per pulse.
