# Filter coffee app UX and user journeys

This is a proposed first version for a personal Android app. Its main job is to help you remember what you brewed, notice what you enjoy in coffee, and repeat a brew with a small adjustment. Product choices below are draft assumptions for review, not implementation commitments.

Use the terms in [the glossary](../GLOSSARY.md). On forms, use familiar labels such as "Coffee dose, g", "Grind setting, ticks", and "Variety / cultivar". Keep "ticks" as the user's unit; "clicks" is another common label for a stepped grinder's adjustments.

See [the UI design direction](ui-design.md) for the owner's visual preferences and grinder dial interaction concept.

## Scope and defaults

The first version stores beans, grinders, recipes, and brew records on the device. It works offline, with no account or sign-in. Cloud sync, sharing, inventory quantities, analytics, equipment integrations, and guided timers are outside this draft's scope. Local storage means uninstalling the app or losing the device can lose the records; export and backup remain a later product decision.

The app remembers the default grinder, each grinder's last recorded tick setting, and the most recently used beans and recipe. It shows these choices before each brew so the user can correct them. The app cannot know the physical grinder's current setting; the UI calls it "Last recorded setting".

Recipes describe the brewing plan. Beans, grinder, and tick setting belong to an individual brew record, so the same recipe can be used with another coffee or grinder.

## Navigation

Use three bottom navigation destinations:

- Brew: the everyday starting point, with "New brew", the last recorded grinder setting, and the most recent brew's "Repeat" action.
- History: completed brew records, newest first. Open a record to inspect, edit, or repeat it.
- Library: recipes, beans, and grinders, grouped into three plainly labeled lists.

Create and edit screens open above these destinations. Android Back returns to the previous screen. Unsaved form edits prompt "Discard changes?" with "Keep editing" and "Discard". An active brew draft saves automatically and offers "Resume brew" on the Brew screen after navigation or an app restart. Support one active draft at a time for this version.

## First brew journey

1. Open the app to an empty Brew screen with "Record your first brew" and a short explanation of what will be saved. Skip a separate onboarding slideshow.
2. Tap "New brew" to open setup. Required rows show "Add beans", "Add grinder", and "Choose or create recipe".
3. Add a grinder by name, enter its current tick setting, and optionally describe the reference point used for counting ticks. The first grinder becomes the default.
4. Add beans with a name and roaster. The user may fill in origin, variety, and expected tasting notes now or later.
5. Create a recipe with a name, dose, ratio, and pulses. A running total helps the user make the pulses match the target water amount.
6. Return to setup with the new items selected. Review the tick setting and recipe summary, then tap "Use this plan".
7. Read the brew sheet while preparing the coffee. It shows the chosen beans, grinder setting, dose, ratio, and ordered pulse instructions.
8. Tap "Finish brew", optionally add observations, then tap "Save brew". The app opens the saved record and makes it available in History.

Creating a library item during setup returns directly to that setup without losing other choices. Save is available once required fields are valid; optional metadata never blocks a brew.

## Everyday journey

Open Brew, tap "New brew", and review the remembered beans, recipe, grinder, and tick setting. Change only what differs today. Tap "Use this plan", brew from the sheet, and save with optional observations.

For a small grind adjustment, change "Grind setting, ticks" in setup. Saving the brew records that value and updates that grinder's last recorded setting. Discarding the draft does neither. The grinder detail screen also has an explicit "Update last recorded setting" action for adjustments made without logging a brew.

"Repeat" on a historical brew copies its saved beans, grinder, tick setting, and recipe plan into a new draft. It shows the old setting even if the grinder's current recorded setting differs. Setup displays both values in that case, for example "This brew: 24 ticks. Last recorded: 26 ticks." The user chooses the setting before continuing. Repeating never changes the original record.

## Library forms

### Beans

Require a name and roaster. Keep these as text fields rather than separate roaster records. Example name: "Ethiopia natural".

Offer optional origin, variety / cultivar, processing method, expected tasting notes, and general notes. Origin and variety accept free text so blends and incomplete bag labels do not need special workflows. Prompt "Country, region, or farm if known" for origin. Expected notes might read "Blueberry, cocoa"; the user's experience goes into brew observations instead.

Allow edits and archiving. Archived beans disappear from normal selection but remain readable in History. A repeat using archived beans identifies them and offers "Use anyway" or "Choose other beans".

### Grinders

Require a name. Offer a last recorded setting in whole ticks and optional reference notes, such as "Count from my usual zero mark". Do not assume a shared zero, tick range, or direction of adjustment across grinders. Do not instruct the user to force the burrs closed to establish a reference.

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

Allow removing pulses and moving them up or down. Recalculate cumulative water and timing after every change. Waits belong to the pulse they follow. If a pulse becomes last, remove its wait and announce that change; if it later gains a successor, its wait starts at zero. A recipe must retain at least one pulse.

Below the list, show "Planned pours: 240 g / Target: 240 g". If the rounded pulse sum differs from the target, show the difference, such as "Add 20 g across your pulses to reach 240 g". Keep the edits on screen but block saving until they match. Changing dose or ratio recalculates the target without silently scaling the pulses; explain the resulting mismatch beside the total.

Allow editing, duplicating, and archiving recipes. A brew-specific recipe adjustment is outside the first version: duplicate or edit the recipe before using it. Existing brew records retain the recipe as it was at the time.

## Brew sheet and timing

The brew sheet presents a static schedule with elapsed start times, per-pulse amounts, cumulative amounts, pour durations, and waits. Start time zero means the start of the first pour. Each following start time equals the previous start plus its pour duration plus its wait.

An illustrative 15 g, 1:16 recipe has three pulses. Pour 60 g over 10 seconds, then wait 30 seconds. At 0:40, pour another 90 g over 20 seconds, then wait 20 seconds. At 1:20, pour the final 90 g over 20 seconds. The cumulative amounts are 60 g, 150 g, and 240 g. Scheduled pouring ends at 1:40.

These numbers demonstrate the UI and calculations; they are not a recommended recipe. Final drawdown can continue after the scheduled pours. The app does not treat the end of the last pour as the completion of the brew. The user decides when to tap "Finish brew". A live timer, alarms, automatic advancement, and measured total brew time are deferred.

## Saving and reviewing a brew

"Finish brew" opens a short review with optional brew observations. Show the chosen plan and equipment, with a way back to correct setup before saving. Prefill the brew date and time when the draft starts and allow editing it to log an earlier brew.

A completed record stores the recipe plan, beans' details, grinder name, and tick setting as they were used, along with date/time and observations. Later library edits do not rewrite history. Planned amounts and durations remain labeled as planned; the first version does not measure or collect actual water amounts or pulse timings.

History rows show date, beans, recipe, and tick setting. The detail screen includes the grinder name and full saved plan. "Edit brew" permits explicit corrections to the record without editing library items or changing the grinder's current recorded setting. "Delete brew" requires confirmation and removes only that record. Library items use archive rather than deletion in this version.

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

Use numeric keyboards for quantities and durations, readable unit labels, and inline errors next to the relevant fields. Keep primary actions reachable above the keyboard. Support large text and screen readers; read cumulative water with its meaning rather than as an unlabeled number. Use text as well as color for errors and selected defaults. Pulse reordering must work through labeled buttons, without requiring dragging.

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

## Assumptions to revisit

This draft assumes whole ticks, optional bean metadata beyond name and roaster, a static brew sheet, and recipes with absolute water amounts per pulse. The most useful next design decision is whether you want a guided timer during brewing or primarily a record to consult and save. Either choice can preserve the recipe and brew-record distinction.
