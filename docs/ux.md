# Filter coffee app UX and user journeys

This is a proposed first version for a personal Android app. Its main job is to help you remember what you brewed, notice what you enjoy in coffee, and repeat a brew with a small adjustment. Product choices below are draft assumptions for review, not implementation commitments.

Use the terms in [the glossary](../GLOSSARY.md). On forms, use familiar labels such as "Coffee dose, g", "Grind setting, ticks", and "Variety / cultivar". Keep "ticks" as the user's unit; "clicks" is another common label for a stepped grinder's adjustments.

See [the UI design direction](ui-design.md) for the owner's visual preferences and grinder dial interaction concept.

## Scope and defaults

The app stores beans, grinders, recipes, and brew records on the device and works offline without an account. Signing in with Google is optional and keeps them in the user's account, so they return after a reinstall or on a new phone (see [Account and sync](#account-and-sync)). An optional Bluetooth coffee scale can guide a brew (see [Brewing with a scale](#brewing-with-a-scale)); other equipment integrations, sharing, inventory quantities, and analytics are outside this draft's scope.

The app remembers the default grinder, each grinder's last recorded tick setting, and the most recently used beans and recipe. It shows these choices before each brew so the user can correct them. The app cannot know the physical grinder's current setting; the UI calls it "Last recorded setting".

Recipes describe the brewing plan. Beans, grinder, and tick setting belong to an individual brew record, so the same recipe can be used with another coffee or grinder.

## Navigation

Use four bottom navigation destinations:

- Brew: the everyday starting point. "New brew" floats at the bottom of the screen, and becomes "Resume brew" while a brew is in progress. Above the most recent brew, a card shows a brew in progress, another the drafts from Claude waiting, and one for each older brew with tasting notes from Claude waiting. The most recent brew shows its beans, its recipe, dose, ratio, and grind setting, the start of its tasting notes and its "Next time" reminder, then "Repeat" and the tasting notes action.
- History: completed brew records, newest first. Open a record to inspect, edit, or repeat it.
- Library: a hub with a card for each of recipes, beans, and grinders, each showing its count and one useful value: the most used recipe, the beans brewed last, the default grinder and its setting. A card opens that kind's own list, with "Add" in its header. Tapping Library again returns to the hub.
- Settings: account, appearance, the brew timer, the coffee scale, experiments, and about and support. It has no Back arrow; Android Back on History, Library, or Settings returns to Brew.

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

### Brewing from the Library

A recipe's page offers "Brew with this recipe", and active beans offer "Brew with these beans", beneath the item's summary. Either opens a new brew's setup with that item chosen and the remembered choices for the rest. Leaving that setup untouched discards it and returns to the item. While a brew is in progress, the action is unavailable, with "A brew is already in progress." and "Resume brew". Archived beans and recipes offer no brew.

### Beans

Require a name and roaster. Keep these as text fields rather than separate roaster records. Example name: "Ethiopia natural".

Offer optional origin, variety / cultivar, processing method, expected tasting notes, and general notes. Origin and variety accept free text so blends and incomplete bag labels do not need special workflows. Prompt "Country, region, or farm if known" for origin. Expected notes might read "Blueberry, cocoa"; the user's experience goes into brew observations instead.

Offer an optional roast date, chosen from a calendar that stops at today, with "Clear".

#### Batches

Buying the same beans again is a new batch, not new beans: "Bought again" on the beans' page asks for the new bag's roast date and adds it. Beans added before batches existed keep their first bag as "Original bag". Buying archived beans again unarchives them; archiving stays one switch for the beans as a whole. The beans' page lists batches newest first, by when they were added, the newest marked "Current", each with how long ago it was roasted, when it was bought and how many brews used it. A batch can be deleted, after asking, while another remains; brews keep the batch they used. With one batch or none, the beans form edits its roast date; with more, each batch keeps its own.

A brew uses the batch chosen last for those beans, or the newest. With one batch there is nothing more to choose; with two or more, setup offers "Change batch", which starts the scale steps again if they had begun. "Repeat" on a brew whose beans were bought again since uses the newest batch and says so: "Using newest batch (Roasted 15 Jan) · Original brew used 10 Nov batch". "Edit brew" can change a record's batch.

Days off roast count calendar days on the phone: the Library and setup count to today, a record to the day it was brewed ("12 days off roast", "1 day off roast"). A roast date after that day shows no count.

Allow edits, archiving and deletion. Archived beans disappear from normal selection but remain readable in History. A repeat using archived beans identifies them and offers "Use anyway" or "Choose other beans".

"Delete beans", in red in the "More actions" menu, asks "Delete these beans?" with "Existing brew records keep their saved details. You can't undo this.", "Delete" and "Keep beans". Brew records keep the beans as they were, and the beans stop being the remembered choice for the next brew. A brew draft using deleted beans, including a repeat and beans deleted on another phone, shows "These beans were deleted." in setup and in Finish brew, with "Choose other beans" and "Discard brew", and can't continue or save until one is chosen. With no other active beans the first action reads "Add beans" and opens the beans form.

In the Library, swiping a beans row left archives it with a short swipe or deletes it with a long one, each offering "Undo" for 5 seconds instead of asking first. Once any beans are archived, "Archived beans" under the beans list opens a list of them. Opening one shows its details marked "Archived", with "Unarchive beans" in place of "Archive beans" in "More actions", beside "Delete beans". Unarchiving needs no confirmation and returns the beans to the Library list and to selection. On the archived list, a short swipe unarchives beans and a long one deletes them, each with "Undo".

### Grinders

Require a name. Offer a last recorded setting in whole ticks from 0 through 100, inclusive, and optional reference notes, such as "Count from my usual zero mark". Keep the range visible as neutral helper text. Do not assume a shared physical zero or direction of adjustment across grinders. Do not instruct the user to force the burrs closed to establish a reference. The half-wheel dial and direct numeric entry use the same range. The dial has no visible increase or decrease buttons; retain accessible wheel adjustment semantics.

With one grinder, select it automatically. With several, let the user choose a default on a grinder's page; the grinder list marks it "Default". Selecting another grinder loads its own recorded setting, never the previous grinder's tick count. Require a setting in brew setup if the selected grinder has none.

Allow renaming and archiving. Archiving the default requires selecting another active grinder, or clears the default if none remain. Old brew records keep their original grinder information. Repeating an archived grinder requires choosing an active grinder before continuing.

### Recipes

Require a name, positive coffee dose in grams, and at least one pulse. Dose, water and ice accept up to one decimal place; durations use whole seconds. Show units beside every numeric field.

A recipe is "Hot" or "Iced". An iced recipe can give "Ice in server, g", the ice put in the server before brewing; a hot recipe keeps none. Optional details sit under "Details": water temperature (1 to 100 °C), a target finish time such as 2:10, counted from the first pour, the grind size in words, such as "Medium-fine", the source's web address (http or https only), and preparation notes.

The pours set the water and the ratio: total brew water is the pulses' total, rounded to 0.1 g, and the ratio is that water over the dose, shown to one decimal, such as "1:9.4" for 150 g over 16 g. An iced recipe with its ice also shows the ratio with ice, "1:13.8 with ice", and "In the cup": water and ice together. Total brew water is water added, not the beverage collected in the cup.

Each pulse editor contains:

- Optional label, such as "Bloom". Otherwise show "Pulse 1", "Pulse 2", and so on.
- Water to add in grams, required and greater than zero.
- Pour duration in seconds, required and greater than zero.
- Wait after pouring in seconds, zero or greater, for every pulse except the last.
- An optional step note, such as "Stir gently 3 times", behind "Add note". It shows on the brew sheet and the timer during that pour and its wait.

Show both the amount to add and the cumulative scale reading. For example, "Add 90 g • Pour to 150 g total". Cumulative readings assume the scale is tared before the first pour and stays untared between pulses.

"Add pulse" copies the previous pulse's water amount and pour duration into a separate editable pulse. Copy its wait when one exists; otherwise prefill zero. Labels and step notes are not copied. When the previous last pulse gains a following pulse, show its wait field with zero prefilled. The new last pulse has no wait field. Explain this behavior beside the add action so a needed wait is not overlooked.

Allow removing pulses and dragging them by a clearly labeled handle to rearrange them. Keep labeled move up and move down actions available for accessibility. Recalculate cumulative water and timing after every change. Waits belong to the pulse they follow. If a pulse becomes last, remove its wait and announce that change; if it later gains a successor, its wait starts at zero. A recipe must retain at least one pulse.

Changing the dose never scales the pulses silently: the pours stay as they are, so the ratio moves. The form says so, "Coffee dose changed from 16 g to 18 g. Pours still total 150 g, so the ratio is stronger than before.", and offers "Scale pours to keep 1:9.4", with the pours it would give beneath. Scaling works in whole tenths of a gram: each pulse scales, the last takes the rounding, and no pulse drops below 0.1 g. It scales the ice too.

Allow editing, duplicating, and archiving recipes. On a Library item's screen these sit in the header, as "Edit" and a "More actions" menu, so a long recipe never hides them. A brew-specific recipe adjustment is outside the first version: duplicate or edit the recipe before using it. Existing brew records retain the recipe as it was at the time.

### Drafts from Claude

With Brewprint connected to Claude, Claude can draft beans or a recipe from a product page, a photo of a bag, or a recipe link. Drafts reach the phone when it syncs, so they need a signed-in account.

The Library's hub shows them first, in a "Drafts from Claude" card, each as its name with a "Beans" or "Recipe" tag and the roaster or "[dose] g · 1:[ratio] · [water] g" beneath. While any wait, the Brew screen shows "Review 1 draft from Claude" (or "Review 2 drafts from Claude") above the most recent brew, with the drafts' names beneath, which opens the Library.

Opening a draft shows the usual beans or recipe form, filled in and titled "Review beans" or "Review recipe", with the same validation. "Add to Library" saves it, with any edits, and opens it as a Library item. Back leaves the draft waiting; unsaved edits ask "Discard changes?" first. The header's red trash action, "Discard draft", asks "Discard this draft?" with "It won't be added to your Library.", "Discard" and "Keep draft". To have Claude fix a draft instead, ask it in the conversation; its correction replaces the draft on the next sync, including on an open review the user hasn't edited. A review whose draft was added on another phone or discarded closes.

## Brew sheet and timing

The brew sheet presents a static schedule with elapsed start times, per-pulse amounts, cumulative amounts, pour durations, and waits. Start time zero means the start of the first pour. Each following start time equals the previous start plus its pour duration plus its wait.

An illustrative 15 g, 1:16 recipe has three pulses. Pour 60 g over 10 seconds, then wait 30 seconds. At 0:40, pour another 90 g over 20 seconds, then wait 20 seconds. At 1:20, pour the final 90 g over 20 seconds. The cumulative amounts are 60 g, 150 g, and 240 g. Scheduled pouring ends at 1:40.

These numbers demonstrate the UI and calculations; they are not a recommended recipe. Final drawdown can continue after the scheduled pours. The app does not treat the end of the last pour as the completion of the brew. The user decides when to tap "Finish brew".

### Brew timer

The brew sheet offers "Start timer" and "Finish without timing". The timer is optional; an untimed brew works as before. See the [brew timer mockups](design/mockups/brew-timer/index.html) for appearance.

- **Recipe details.** Setup shows the recipe's grind size beside the grind setting, and its style, ice and water temperature with its numbers. The brew sheet lists the details in a line above the pours and each pour's step note. During a pour or the wait after it, the focus card shows that pulse's note. At drawdown it shows the last pulse's note, the target finish time, and the preparation notes behind "Preparation notes". Finish brew shows the target beside the total, and the record lists every detail under Preparation, with the source to open. The live notification stays as it is.
- **Steps.** A step is a pour or a wait. The timer moves through the recipe's steps on schedule. A zero wait starts the next pour immediately. After the last pour the sheet shows "Drawdown" and brew time keeps counting until "Finish brew".
- **Focus card.** It leads with the state word, "Pour", "Wait", "Paused" or "Drawdown", then the cumulative scale reading to pour to and the seconds left. A plan strip shows the whole recipe, and "Next" lists up to two upcoming rows (one while a scale guides the brew, to make room for its curve). "N done, show plan" opens the full plan, which stays open until "Back to current step", the Back arrow, or Android Back, each of which returns to the current step.
- **Skip.** The skip button is labelled by its effect, in two or three words so it fits a phone: "Pour done" during a pour, "Start pulse N" during a wait. Skipping moves every later step earlier and keeps their durations. The button ignores taps for 1 second after a step changes. "Undo" is offered for 5 seconds and restores the schedule as it was, even if a step has changed on its own in that time.
- **Pause.** "Pause" holds the schedule while brew time keeps counting. "Resume" continues the held step; "Start pulse N" ends the pause and starts the next pour. Pause is not offered during drawdown.
- **Finish.** "Finish brew" stops the clock and opens the review with "Total brew time", which can be corrected. Back from the review restores the timer as it was, including a pause.
- **A forgotten brew.** The timer stops itself 15 minutes after the last pour ends, or 15 minutes after a pause began. The Brew screen then shows when it stopped, and "Resume brew" opens the review. The total is "Not recorded" until the user enters one.
- **Closing the app.** The timer is kept as timestamps in the draft, so reopening the app shows the correct step and brew time.

### Timer settings and live notification

Settings holds the appearance choice and four brew timer switches: "Live notification" (off by default), "Vibrate at each step", "Sound at each step", and "Keep screen on while brewing" (all three on by default). The sound is a short tone at each step change, for a phone propped on the counter where a vibration goes unfelt.

An "Experimental" section near the end of Settings holds features still being tried out. Its first is "Theme": "Monochrome" (the default) or "Blueprint". The choice is saved on the phone, like appearance, and applies at once. Both themes offer the same screens, actions, and accessibility; only the look differs. See [the blueprint theme](ui-design.md#experimental-blueprint-theme).

"About and support" ends Settings. It shows the app's version, as the version name and build number, for example "0.3.0 (42)". "Export data" opens Android's save dialog with "brewprint-export-<date>.zip" filled in. The file holds `about.txt` (the app's version, the Android version, the phone's model, and when it was exported), `data.json` (everything the app keeps on the phone: beans, grinders, recipes, brews, the brew draft, Claude's drafts and settings, or the saved file as it is if the app couldn't read it), and `log.txt` (the app's recent lines from Android's log, which Android keeps only until it needs the room). Sign-in tokens are removed from the log. Once saved, "Export saved" shows under the button; a failure says "Couldn't export your data. Try again, or save it somewhere else." Cancelling the save dialog says nothing. "Report a problem" opens a new issue on Brewprint's GitHub page in the browser, where the export can be attached.

Turning "Live notification" on is the only moment the Android notification permission prompt appears. If notifications are refused or turned off, a "Blocked by Android" notice appears under the switch with "Open Android settings". The in-app timer works the same either way.

On Android 16, the user can also turn off Live Updates for Brewprint alone. ColorOS calls this "Show Live Updates on Live Alerts". If notifications are allowed but Live Updates are off, a "Live Updates are off" notice appears under the switch instead. Its "Open Android settings" opens Brewprint's Live Updates setting, or its notification settings on phones without that page. The brew still shows as a normal notification.

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

1. **Brewer.** "Put the brewer on the scale" with the live reading and "Tare". An iced recipe with its ice given first says "Put 70 g ice in the server first", so the tare includes the ice.
2. **Coffee.** After the tare, the card shows only "Taring the brewer" until the scale reads about 0 g, then asks for the coffee and shows its reading, labelled "Dose ready" (and announced) once it holds steady. Choosing other beans or another recipe after the scale steps began starts them again, with the recipe's own dose. If the scale disconnects before a dose is chosen, even with the sheet closed, the card returns to "Put the brewer on the scale" with "Tare the brewer again. Take the coffee out first if you've added it." When the reading differs from the plan, a line says that using it adjusts the pours to keep the ratio. Once it holds steady above about 0 g (1 g), it offers "Use 15.2 g" and "Keep 15 g". "Use" records the weighed dose and rescales this brew's pulses to keep the recipe's ratio: 15.2 g at 1:16 becomes 243.2 g, each pulse scaled and rounded to 0.1 g, the last pulse taking the rounding. The ice stays as the recipe gives it. The Library recipe does not change. "Keep" keeps the recipe's dose. Coffee the scale can't weigh, such as grounds that went in before the tare, reads about 0 g, so tapping the weight (marked with a pencil) opens "Coffee weight", filled with the reading or the planned dose. "Use weight" works as "Use" with the typed amount, which must be positive with up to one decimal place, and the dialog says when it adjusts the pours. Either way the app then tares the scale, so the scale's own display matches the cumulative targets. If the scale does not reach about 0 g within 1.5 seconds of a tare, the card leads with "The scale didn't tare", says "Tap Tare again, or press the scale's tare button.", and offers "Tare"; the sheet does not arm until the scale reads about 0 g.
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

A scale brew's record also shows, in "Timing and water", a measured row (dose, water, ratio), the water curve over a faint line of the plan (rising during each planned pour, flat during each wait) and the total brew water, a shaded flow band along the bottom worked out from the saved readings, ending when the dripper came off and with a gap while the brewer is off the scale, and a "Reached" column with the steady reading after each pulse, or "—" when the scale gave no steady reading before the next pulse's water began. A note under the table says what "—" means. If the next pulse begins before the reading settles, after a late pour's short wait or "Start pulse N", a steady reading still counts until the reading rises 2 g above where that pulse began, or above the last pulse's target if its pour stopped up to 1 g short of it. Water stays on the scale, so a steady reading seen after a gap, within the same wait, still counts. The tasting journal stays first on the record.

History rows show date, beans, recipe, and tick setting. The detail screen includes the grinder name and full saved plan. "Edit brew" permits explicit corrections to the record without editing library items or changing the grinder's current recorded setting. "Delete brew" removes only that record and offers "Undo" for 5 seconds, which restores it. In History, swiping a row left deletes it the same way and swiping right repeats it. Recipes and grinders use archive rather than deletion in this version; beans can be deleted.

If a check stops the save, for example a grinder archived since setup, retain the draft and show why, or "Couldn't save brew. Try again." Do not show a completed record or update remembered values until saving succeeds. Repeated taps on Save must create only one record.

Changes take effect at once and are written to the phone's storage in the background, so a large journal never slows the screen or the scale. If that write fails, for example because storage is full, the app says "Couldn't save your latest changes on this phone. Free up storage; they are saved again with your next change." Each write holds everything, so the next one that succeeds saves the changes the failed one missed.

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

### Tasting notes from Claude

With Brewprint connected to Claude, the user can talk through a cup with Claude, which then drafts the tasting notes for that brew from what the user said. The notes reach the phone when it syncs and wait on the brew; they never replace its notes until the user saves them.

While Claude's notes wait, the brew's tasting notes action reads "Review tasting notes from Claude", on the record and on the most recent brew's card. An older brew gets its own card on Brew, "Review tasting notes from Claude" with the beans' name and the brew's date beneath. The review is the usual tasting notes form, titled "Review tasting notes", filled in with Claude's notes, which start from the brew's own notes, and with "Explore the taste" open when Claude filled in any guided field. A line beneath the beans' name says "Claude filled these in from your conversation. Check them, change anything, then save." "Save notes" saves them, with any edits, as the brew's notes, in place of any unsaved note edits. Back leaves Claude's notes waiting; unsaved edits ask "Discard changes?" first, and are not kept. The header's red trash action, "Discard draft", asks "Discard Claude's notes?" with "Your brew keeps the notes it has.", "Discard" and "Keep draft". To have Claude fix the notes instead, ask it in the conversation; its correction replaces them on the next sync, including on an open review the user hasn't edited. A review whose notes were saved or discarded on another phone closes. Deleting a brew discards Claude's notes for it.

For example, the user saves a brew before tasting, returns from the Brew screen, writes "Smells like cocoa; lighter than I expected", selects Light body and silky mouthfeel, and adds "Sweeter as it cooled" later. On their next visit to that record, those observations sit beside the exact recipe and tick setting that produced the cup. The app makes no automatic diagnosis or grinder adjustment from tasting notes.

## Account and sync

Settings opens with "Account". Signed out, it explains what signing in keeps and offers "Sign in with Google", which shows Android's Google account picker. Cancelling the picker says nothing; a failure says "Couldn't sign in. Check your connection and try again."

Signed in, it shows the account's email, a sync status ("Syncing…", "Synced 5 minutes ago", or "Couldn't sync. It will try again."), and "Sign out". "Sign out" asks first: "Sign out?" with "Your beans, grinders, recipes and brews will be removed from this phone. They stay in your Google account.", and "Sign out" and "Cancel".

- **What syncs.** Beans, grinders, recipes, and brew records, one item at a time. The brew draft, unsaved note edits, appearance, theme, timer settings, and the paired scale stay on the phone. Each sync also fetches Claude's drafts, beans, recipes and tasting notes; adding, saving or discarding one deletes it from the account.
- **When.** On sign-in, when the app opens, and a couple of seconds after each change. Offline changes wait and go up next time.
- **Signing in.** Anything made on the phone while signed out joins the account; a fresh install receives everything in the account.
- **One account's data at a time.** Accounts never merge. Signing in to a different account than the phone last synced with replaces the phone's beans, grinders, recipes, brews, and brew draft with that account's. If the earlier account still has changes waiting to upload, for example after its session ended while offline, sign-in is refused with "This phone has changes that haven't reached the Google account you used before. Sign in with that account first."
- **Conflicts.** The newest change to an item wins. A change not yet uploaded from this phone wins over the server's copy.
- **Signing out** uploads every change first, then removes the account's beans, grinders, recipes, brews, and brew draft from the phone. Settings and the paired scale stay. If changes can't be uploaded, it says "Couldn't upload your latest changes. Connect to the internet and try again." and nothing is removed.

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
- After signing in, uninstalling, reinstalling, and signing in again, the library and History come back.
- A brew deleted on one phone disappears from another signed in to the same account.
- Signing out and in to a second account shows only the second account's library and History.

## Assumptions to revisit

This draft assumes whole ticks, optional bean metadata beyond name and roaster, and recipes with absolute water amounts per pulse.
