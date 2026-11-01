# UI design direction

This document records the owner's visual and interaction preferences for Brewprint. It is a working design brief, with confirmed preferences separated from proposals and open questions.

Use the vocabulary in [the glossary](../GLOSSARY.md) and the journeys in [the UX draft](ux.md). The UX draft describes proposed behavior; this brief adds the visual direction. Typography option A has been selected; exact screen layouts and implementation remain undecided.

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
- Use the understated typography of option A from the generated comparison.
- Reduce unnecessary subtext. Every line should help the user understand information or take an action.

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
- Use consistent custom typography, controls, and navigation styling across Brew, History, and Library.

Exact palette values, any limited accent colors, typefaces, corner radii, and the specific placement of selective glass effects are undecided. Offer System, Light, and Dark appearance choices, with System as the default.

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
- Provide direct numeric entry and labeled increase and decrease controls as alternatives to the wheel gesture.
- Respect device haptic preferences. The setting must remain usable without vibration and with reduced motion.
- Keep the wheel clear of system edge gestures, and keep its controls reachable at larger text sizes.
- Do not infer a universal tick range, zero point, or finer/coarser direction. Each grinder has its own reference.

These details extend the whole-tick entry described in the UX draft. They do not connect to or change the physical grinder.

## Remaining refinements

- Exact light and dark palettes and any limited accent colors.
- Which navigation elements or overlays receive the selective glass effects.
- Exact typeface and sizes within the selected understated typography direction.
- Exact corner radii for the softly rounded rectangles.
- Initial wheel materials, default material, lighting, and where the user selects a material for each grinder.
- Wheel gesture and rotation direction.
- Dial opening animation and dismissal interaction, with no separate confirmation step.
