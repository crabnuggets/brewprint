# Filter coffee brewing

Language for describing reusable filter coffee recipes and the individual brews made with them.

## Language

**Recipe**:
A reusable plan specifying coffee dose, brew ratio, pulses, and preparation notes.
_Avoid_: Brew record, brew session

**Brew record**:
A record of one preparation of coffee, including its recipe, beans, grinder, grind setting, and optional observations.
_Avoid_: Recipe

**Beans**:
A named coffee with a roaster, origin, variety, and expected tasting notes. A blend may have multiple origins or varieties.

**Dose**:
The mass of dry coffee used for a brew, measured in grams.
_Avoid_: Serving size

**Brew ratio**:
The ratio of dry coffee mass to brew water mass, written as 1:N. A ratio of 1:16 means 16 grams of water per gram of coffee.

**Total brew water**:
The total mass of water poured onto the coffee, measured in grams.
_Avoid_: Beverage yield, final cup amount

**Pulse**:
One pour in a recipe, with a specified water amount and pour duration. A wait separates it from the next pulse.

**Pour duration**:
The planned time from starting a pulse to finishing its pour.

**Wait duration**:
The planned interval from the end of a pulse's pour to the start of the next pulse.

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
