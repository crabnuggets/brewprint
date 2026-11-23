# UI design direction

This document records the owner's visual and interaction preferences for Brewprint. It is a working design brief, with confirmed preferences separated from proposals and open questions.

Use the vocabulary in [the glossary](../GLOSSARY.md) and the journeys in [the UX draft](ux.md). The UX draft describes proposed behavior; this brief adds the visual direction. Typography option A has been selected. The owner accepted the [v1 mockups](design/mockups/README.md) as a rough visual reference. Use them as the appearance baseline while adapting layouts for implementation and accessibility.

## Confirmed direction

- The app should feel crisp, clean, and premium.
- Avoid a coffee or cafe theme. The subject matter does not need to determine the colors or decoration.
- Use some glassmorphic styling.
- Keep glass effects selective.
- Use a custom visual identity and avoid stock Android styling.
- Prefer spacious screens with fewer details visible at once.
- Support both light and dark appearances with a mostly monochrome palette.
- Follow the device appearance by default, with an in-app override for light or dark appearance.
- Use softly rounded rectangles for controls and containers.
- Use softly filled form fields with subtle rounded borders. Show required indicators in a neutral tone, and show errors after the user edits or leaves a field. Untouched empty fields stay neutral; invalid required values still block saving.
- Use a custom monochrome floating bottom navigation with a softly rounded selected state and no inner rectangular fill or shadow. Keep it compact: the selected destination shows its icon and label side by side in a soft pill, and the other destinations show icons only, with their names kept for screen readers. When a sync fails, Settings carries a small dark dot, never red, that screen readers hear as "Sync failed".
- Place an icon Back action beside the title on create, edit, detail, and picker screens.
- Use the understated typography of option A from the generated comparison.
- Reduce unnecessary subtext. Every line should help the user understand information or take an action.
- Stay monochrome, and separate levels by contrast and weight rather than color. Section titles are darker and heavier than body text; secondary labels are lighter. A title, a label, and an action should never share one tone.
- The one accent is red, kept for actions that delete or discard: "Discard brew", "Delete brew", "Delete beans", and a History or beans row swiped past its delete threshold. A destructive button has a soft red fill with darker red text; a swiped row turns solid red. Archiving stays neutral, since brew records keep the archived item's details. The one exception is amber, used only for a beans row swiped past its archive or unarchive threshold.
- Header actions on a screen are 40 dp round icon buttons with no text, beside the title on the right: a red trash icon for "Discard brew" on setup and Finish brew, and on a Library item a pencil for "Edit" with a ⋮ "More actions" menu for duplicating, archiving or unarchiving and, for beans, deleting. Each keeps its name for screen readers and as a long-press tooltip.
- Do not outline buttons. A primary action is filled near-black (near-white in dark appearance). A secondary action has a soft gray fill. Text-only actions also get a soft fill so they read as buttons, not prose. Buttons are softly rounded rectangles (16 dp), not pills, and shrink slightly while pressed. Use an icon on an action only where it adds meaning, such as Pause, skip, and Save.
- Show an inline "Change …" action as a 40 dp round icon button with a soft fill, beside the value it changes: a pencil for a value edited in place (date, total brew time), and a sliders icon for "Change setup", which leaves the screen. Each keeps its name for screen readers and as a long-press tooltip, and has a 48 dp touch target. Full-width actions such as "Repeat" and "Edit brew" keep their text labels.

## Screen chrome while scrolling

- **Header.** A screen's header, with its title, Back action, and header actions, slides away as the content scrolls down and returns as soon as it scrolls up. Android Back keeps working while it is hidden. On the brew sheet the header stays while a timer runs, so the scale status is always in view.
- **Bottom navigation.** The floating navigation hides as the content scrolls down and returns when it scrolls up or reaches the end.
- **Floating action.** "New brew", or "Resume brew" while a brew is in progress, floats at the bottom right of Brew, above the navigation. It is a filled primary button with the small corners, and it drops to the corner while the navigation is hidden. It stays in view while TalkBack is on, and screen readers reach it after the screen's content.
- **Footer actions.** Actions pinned to the bottom of a screen, such as Pause, the skip action, and "Save brew", never hide. On the running brew sheet they take one row of buttons with "Finish brew" as a smaller action beneath.

## Lists and gestures

Swiping a History row left deletes that brew, with "Undo" for 5 seconds. Swiping it right repeats it. The same actions stay on the brew record, and screen readers offer them as actions on the row, so swiping is never required. In the Library, beans rows swipe left: a short swipe, past 30% of the row, archives the beans and turns the row amber; a long one, past 65%, deletes them and turns it red. Each threshold gives the same haptics as History, and both actions offer "Undo" for 5 seconds and are offered to screen readers as actions on the row. On the archived beans list, the short swipe unarchives instead, also in amber. Recipe and grinder rows do not swipe; archiving a grinder can need a choice of replacement. Rows do not swipe while picking beans for a brew.

While a row is swiped, the action behind it shows on soft gray. Once the swipe passes the point where letting go acts (40% of the row), the phone gives one firm haptic, the fill changes over about 120 ms (to red for Delete, to the solid primary fill for Repeat), and the icon grows slightly. Pulling back under that point gives a lighter haptic and returns to gray. Haptics follow the phone's own settings.

## Motion

Motion should feel calm and continuous rather than snappy: about 250–400 ms with a gentle ease-out, and nothing that bounces.

- **Screens.** Opening a screen slides it in a short distance while it fades in; Back reverses it. Switching destinations cross-fades.
- **Focus card.** On each step change, the state word, labels, and large numbers swap with a short blur cross-fade, for example "On scale" to "Time left" when a pour ends. It starts on the same step change as the vibration and sound, so they land together. Numbers that count within a step, brew time and time left, change in place with fixed-width digits; only the bars move continuously, so the card has one moving thing between steps.
- **Cards.** Cards grow and shrink smoothly when their content changes, such as the full plan, the scale steps, and the Undo bar.
- **Chrome.** The header and bottom navigation slide in and out with the scroll. Button labels that change in place, such as Pause and Resume or the skip label, cross-fade.
- **Reduced motion.** When Android's "Remove animations" is on, every change happens immediately. Where blur is unavailable (before Android 12), the swap is a plain cross-fade.

The Android platform remains the target in the UX draft. Custom styling should retain its Back behavior, accessibility support, and other interaction requirements.

## Typography and copy

Option A uses a neutral modern sans serif, restrained heading sizes and weights, readable body text, and clear numeric values with visible units. Carry this direction into both light and dark appearances. Exact typeface and sizes remain open.

Remove decorative taglines such as "A good cup starts here" from the generated concept. A screen title such as "Brew" and an action such as "New brew" are sufficient without a supporting slogan. Do not replace removed subtext with another tagline.

Avoid subtitles that repeat the title or explain an obvious action. Retain concise help where it affects a choice, explains an unfamiliar field, identifies an error, or distinguishes values such as "Last recorded setting". Place optional explanations beside the relevant control or behind an explicit help action.

## Proposed visual treatment

The following details interpret the confirmed direction and remain open for review.

- Use generous spacing and a clear type hierarchy. Give primary actions and brewing values visual priority.
- Start with concise summaries and reveal secondary details when requested. Keep units, required choices, and validation messages visible where needed.
- Apply restrained translucency, blur, fine borders, and soft highlights to selected floating controls or overlays. Keep reading and form areas clear and legible.
- Keep text contrast reliable over glass effects. Provide an opaque treatment where transparency makes content harder to read.
- Use consistent custom typography, controls, and navigation styling across Brew, History, Library, and Settings.

Exact palette values, any limited accent colors, typefaces, corner radii, and the specific placement of selective glass effects are undecided. Offer System, Light, and Dark appearance choices, with System as the default.

## Experimental: blueprint theme

Settings → Experimental → Theme offers "Blueprint" beside the default "Monochrome". It follows the [revised Redline board](design/mockups/v3-blueprint/b2.png), and is an opt-in experiment: the confirmed direction above, including "Avoid a coffee or cafe theme" and the monochrome palette, still describes the default theme. Both themes keep the same behavior, content, order, and accessibility; a theme may change color, type, shape, decoration, and motion. A screen never shows a value, a line, or an action in one theme that it leaves out in the other, and never in a different order.

- **Paper and type.** Graphite on vellum with an 8 dp grid, heavier every 40 dp. App text is set in Overpass. The user's own words (tasting notes, "Next time") are set in Kalam, like pencil. Cards have 6 dp corners and buttons 10 dp.
- **Red pencil.** One accent, used only for what to act on now or what to change next time: the grams to pour to, the target line on the live curve, and "Next time". Late starts in the timing table stay graphite; they are a record, not a task.
- **Title blocks.** Setup values sit in labelled cells in both themes; the blueprint draws them as a title block with ruled cells and drafting lines, where Monochrome uses a soft card with hairlines. On the brew record, Preparation is one such block, and "Next time" closes the tasting journal beneath a double rule. The timing table is ruled to match, inside the same card as the rest of "Timing and water". The latest brew on Brew has a four-cell strip of recipe, dose, ratio, and grind under a double rule. Cells halve, then stack, on narrow screens or with large text.
- **Drafting lines.** A heavy border whose lines run a few dp past each corner, light rules between cells, and a double rule under headings and before the revision row.
- **Cafe touch.** A strip of drafting tape on the latest brew and on the record's tasting notes. Decorative only.

The board's ring and arrow from "Next time" to a setting are not drawn: the app cannot tell which setting a note is about.

## Grinder setting dial

### Confirmed concept

Show the dial only when the user explicitly chooses to adjust the grind setting. Pop up a semi-realistic half wheel on the right side of the screen. Its interaction should emulate turning a physical grinder dial. Give gentle device haptic feedback for each click.

Offer multiple wheel materials or finishes that the user can select per grinder. Each grinder remembers its selected finish. Brushed metal, matte black, and frosted glass are candidates; the initial set and default remain open.

The wheel stops when the user's finger stops, with precise clicks and no momentum after release. Each click represents one whole tick and gives gentle haptic feedback. Keep the visible setting synchronized with those clicks.

Apply changes to the current form or brew draft as the user turns the wheel. Opening it already expresses the intent to adjust, so no separate dial-level Done or Cancel action is needed. Dismissing the dial keeps the selected value in the form or draft. Existing save and discard rules still determine when the grinder's last recorded setting is updated.

The exact gesture, rotation direction, dismissal interaction, and location of the per-grinder material selector still need decisions.

### Proposed behavior and constraints

- Display the selected whole-tick value and its unit beside the wheel.
- Keep the same precise tick behavior across all selectable wheel materials. A material choice changes its appearance.
- Keep the adjustment local to the current form or brew draft. Existing save and discard rules determine when it updates the grinder's last recorded setting.
- Preserve the distinction between a historical brew's setting and the grinder's last recorded setting when repeating a brew.
- Use whole ticks from 0 through 100, inclusive, for every grind setting entry and wheel adjustment.
- Provide direct numeric entry and accessible wheel adjustment semantics. Do not show separate increase or decrease buttons.
- Respect device haptic preferences. The setting must remain usable without vibration and with reduced motion.
- Keep the wheel clear of system edge gestures, and keep its controls reachable at larger text sizes.
- Each grinder has its own reference point. Do not infer a finer/coarser direction from the tick value.

These details extend the whole-tick entry described in the UX draft. They do not connect to or change the physical grinder.

## Remaining refinements

- Exact light and dark palettes and any limited accent colors.
- Which navigation elements or overlays receive the selective glass effects.
- Exact typeface and sizes within the selected understated typography direction.
- Exact corner radii for cards and other containers; buttons use 16 dp.
- Initial wheel materials, default material, lighting, and where the user selects a material for each grinder.
- Wheel gesture and rotation direction.
- Dial opening animation and dismissal interaction, with no separate confirmation step.
