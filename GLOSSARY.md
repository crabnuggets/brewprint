# Filter coffee brewing

Language for describing reusable filter coffee recipes and the individual brews made with them.

## Language

**Recipe**:
A reusable plan specifying coffee dose, pulses, and preparation notes, hot or iced, with optional water temperature, target finish time, grind size and source. Its brew ratio follows from the dose and the pulses.
_Avoid_: Brew record, brew session

**Brew record**:
A record of one preparation of coffee, including its recipe, beans, grinder, grind setting, and optional observations.
_Avoid_: Recipe

**Beans**:
A named coffee with a roaster, origin, variety, and expected tasting notes. A blend may have multiple origins or varieties.

**Batch**:
One bag of the same beans, bought at one time, with its own roast date. Beans keep their batches; a brew record keeps the batch it used.
_Avoid_: Bag (in the app's words), lot

**Roast date**:
The day a batch was roasted, as its bag gives it.

**Days off roast**:
Whole calendar days from a batch's roast date to a day: today in the Library and setup, the day of the brew on a record.
_Avoid_: Age, freshness

**Library draft**:
Beans or a recipe Claude proposes, waiting in the Library until the user adds it, after any edits, or discards it. Shown in the app as a draft from Claude.
_Avoid_: Brew draft, suggestion

**Dose**:
The mass of dry coffee used for a brew, measured in grams.
_Avoid_: Serving size

**Brew ratio**:
The ratio of dry coffee mass to brew water mass, written as 1:N. A ratio of 1:16 means 16 grams of water per gram of coffee. A recipe's ratio is its total brew water over its dose, shown to one decimal.

**Ice**:
For an iced recipe, the grams of ice put in the server before brewing. It is never a scale target and is not part of total brew water; "with ice" ratios add it.

**Total brew water**:
The total mass of water poured onto the coffee, measured in grams.
_Avoid_: Beverage yield, final cup amount

**Pulse**:
One pour in a recipe, with a specified water amount and pour duration, and an optional step note such as "Stir gently". A wait separates it from the next pulse.

**Target finish time**:
When a recipe expects the drawdown to end, counted from the first pour. A guide shown beside the total brew time, never a timer stop.

**Pour duration**:
The planned time from starting a pulse to finishing its pour.

**Wait duration**:
The planned interval from the end of a pulse's pour to the start of the next pulse.

**Step**:
A pour or a wait in a timed brew. A recipe with N pulses has N pours and up to N-1 waits.

**Brew time**:
The running time of a timed brew, counted from the start of the first pour.

**Total brew time**:
The time from the start of the first pour to "Finish brew", saved with a timed brew record. The review can correct it, for example to the moment the dripper was lifted.
_Avoid_: Pour time, extraction time

**Coffee scale**:
A Bluetooth scale that reports weight and flow to the app during a brew. Optional; a brew without one is timed by the clock alone.
_Avoid_: Smart scale

**Weighed dose**:
The dose read from the coffee scale before a brew. Using it rescales that brew's pulses to keep the recipe's brew ratio; the recipe itself is unchanged.

**Measured water**:
The scale's steady reading once flow stops after the last pour. It sits beside the planned total brew water and is never beverage weight.
_Avoid_: Yield

**Reached**:
The scale's steady reading after a pulse's pour, shown beside its cumulative target.

**Bloom**:
An initial wetting of the coffee before subsequent pours. A recipe may label its first pulse as the bloom.

**Drawdown**:
The draining of water through the coffee bed, including the draining that continues after the last pour.

**Grind setting**:
A grinder-specific adjustment recorded in ticks relative to that grinder's reference point.
_Avoid_: Universal coarseness

**Expected tasting notes**:
The flavor descriptions associated with the beans, usually supplied by the roaster.
_Avoid_: Brew observations

**Brew observations**:
The user's notes about a particular brew, including taste and anything to change next time.
_Avoid_: Expected tasting notes

**Tasting journal**:
The personal tasting observations for one brew, combining free text with optional descriptions of aroma, flavor, sweetness, acidity, bitterness, body, mouthfeel, and finish.
_Avoid_: Recipe notes, expected tasting notes

**Body**:
The perceived weight or fullness of coffee in the mouth, described in this app as light, medium, or full.
_Avoid_: Flavor intensity

**Mouthfeel**:
The texture and tactile sensations of coffee in the mouth, described with words such as silky, creamy, or drying. Body describes its weight; mouthfeel allows other texture observations.

**Finish / aftertaste**:
The sensations and flavors that linger after swallowing coffee.
