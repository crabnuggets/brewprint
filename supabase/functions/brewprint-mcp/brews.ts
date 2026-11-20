// The app's synced JSON (app/src/main/java/com/brewprint/data/Models.kt), and the views Claude reads.
// fixtures/brew-record.json is a brew as the app writes it; a Kotlin test fails when the two drift apart.

export interface Beans {
  id: string;
  name: string;
  roaster: string;
  origin: string;
  variety: string;
  processing: string;
  expectedNotes: string;
  notes: string;
  archived: boolean;
}

export interface Grinder {
  id: string;
  name: string;
  lastSetting: number | null;
  referenceNotes: string;
  archived: boolean;
}

export interface Pulse {
  label: string;
  water: number;
  pourSeconds: number;
  waitSeconds: number;
}

export interface Recipe {
  id: string;
  name: string;
  dose: number;
  ratio: number;
  pulses: Pulse[];
  notes: string;
  archived: boolean;
}

export interface Journal {
  impression: string;
  aroma: string;
  flavorTags: string[];
  flavor: string;
  sweetness: string | null;
  sweetnessNotes: string;
  acidity: string | null;
  acidityNotes: string;
  bitterness: string | null;
  bitternessNotes: string;
  body: string | null;
  mouthfeelTags: string[];
  mouthfeel: string;
  finish: string;
  finishLength: string | null;
  asItCools: string;
  nextTime: string;
}

export interface BrewTiming {
  totalSeconds: number | null;
  totalEdited: boolean;
  pausedSeconds: number;
  pulseStarts: (number | null)[];
}

export interface ScaleData {
  weighedDose: number | null;
  water: number | null;
  reached: (number | null)[];
  curve: (number | null)[];
  liftSeconds: number | null;
}

export interface BrewRecord {
  id: string;
  startedAt: number;
  beans: Beans;
  grinder: Grinder;
  setting: number;
  recipe: Recipe;
  journal: Journal;
  timing: BrewTiming | null;
  scale: ScaleData | null;
}

export interface Library {
  beans: Beans[];
  grinders: Grinder[];
  recipes: Recipe[];
  brews: BrewRecord[];
}

/** One `records` row that is not deleted. */
export interface Row {
  kind: string;
  data: unknown;
}

export function library(rows: Row[]): Library {
  const of = <T>(kind: string) => rows.filter((row) => row.kind === kind).map((row) => row.data as T);
  return {
    beans: of<Beans>("beans"),
    grinders: of<Grinder>("grinders"),
    recipes: of<Recipe>("recipes"),
    brews: of<BrewRecord>("brews").sort((a, b) => b.startedAt - a.startedAt),
  };
}

const round1 = (value: number) => Math.round(value * 10) / 10;
const totalWater = (recipe: Recipe) => round1(recipe.dose * recipe.ratio);

/** The journal's filled-in fields only, so empty answers don't read as observations. */
export function tasting(journal: Journal): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(journal).filter(([, value]) =>
      value !== null && value !== "" && !(Array.isArray(value) && value.length === 0)
    ),
  );
}

export interface BrewFilter {
  beans?: string;
  recipe?: string;
  since?: string;
  until?: string;
  limit?: number;
}

/**
 * Newest first. Text filters match any part of the name, ignoring case. A bare YYYY-MM-DD bound is a whole UTC day;
 * a date-time with an offset is exact.
 */
export function findBrews(brews: BrewRecord[], filter: BrewFilter): BrewRecord[] {
  const contains = (text: string, part?: string) => !part || text.toLowerCase().includes(part.toLowerCase());
  const from = filter.since ? Date.parse(filter.since) : -Infinity;
  const to = filter.until ? Date.parse(filter.until) + (filter.until.length <= 10 ? 86_400_000 : 1) : Infinity;
  return brews
    .filter((brew) =>
      contains(`${brew.beans.name} ${brew.beans.roaster}`, filter.beans) &&
      contains(brew.recipe.name, filter.recipe) &&
      brew.startedAt >= from && brew.startedAt < to
    )
    .slice(0, filter.limit ?? 20);
}

/** A brew in a line or two: what was brewed, how, and what the taster wrote. */
export function brewSummary(brew: BrewRecord) {
  return {
    id: brew.id,
    brewedAt: new Date(brew.startedAt).toISOString(),
    beans: [brew.beans.name, brew.beans.roaster].filter(Boolean).join(" · "),
    grinder: brew.grinder.name,
    grindSetting: brew.setting,
    recipe: brew.recipe.name,
    doseGrams: brew.recipe.dose,
    ratio: `1:${brew.recipe.ratio}`,
    totalBrewWaterGrams: totalWater(brew.recipe),
    pulses: brew.recipe.pulses.length,
    totalBrewTimeSeconds: brew.timing?.totalSeconds ?? null,
    measuredWaterGrams: brew.scale?.water ?? null,
    tasting: tasting(brew.journal),
  };
}

/** The whole brew, with the scale's per-second curve turned into the numbers a brewer reads. */
export function brewDetail(brew: BrewRecord) {
  const { scale, timing, recipe } = brew;
  let start = 0;
  let cumulative = 0;
  const pulses = recipe.pulses.map((pulse, index) => {
    cumulative = round1(cumulative + pulse.water);
    const plannedStart = start;
    start += pulse.pourSeconds + pulse.waitSeconds;
    return {
      label: pulse.label || `Pulse ${index + 1}`,
      waterGrams: pulse.water,
      cumulativeTargetGrams: cumulative,
      pourSeconds: pulse.pourSeconds,
      waitSeconds: pulse.waitSeconds,
      plannedStartSeconds: plannedStart,
      actualStartSeconds: timing?.pulseStarts[index] ?? null,
      reachedGrams: scale?.reached[index] ?? null,
    };
  });
  return {
    ...brewSummary(brew),
    beans: brew.beans,
    grinder: { name: brew.grinder.name, referenceNotes: brew.grinder.referenceNotes },
    recipe: { name: recipe.name, dose: recipe.dose, ratio: recipe.ratio, notes: recipe.notes },
    pulses,
    timing: timing && {
      totalSeconds: timing.totalSeconds,
      totalEditedByHand: timing.totalEdited,
      pausedSeconds: timing.pausedSeconds,
    },
    scale: scale && scaleAnalysis(scale),
  };
}

/**
 * Flow is the change in the weight curve from one second to the next. The last pour ends at the last second the
 * weight still rose, which the scale measured, rather than when the recipe planned it to; drawdown runs from there
 * to the moment the dripper came off.
 */
export function scaleAnalysis(scale: ScaleData) {
  const flows = scale.curve.map((grams, second) => {
    const before = scale.curve[second - 1];
    return second > 0 && grams != null && before != null ? round1(Math.max(0, grams - before)) : null;
  });
  const pouring = flows.filter((flow): flow is number => flow != null && flow > 0.5);
  const lastPourEnd = flows.findLastIndex((flow) => flow != null && flow > 0.5);
  return {
    weighedDoseGrams: scale.weighedDose,
    measuredWaterGrams: scale.water,
    lastPourEndedAtSeconds: lastPourEnd >= 0 ? lastPourEnd : null,
    dripperLiftedAtSeconds: scale.liftSeconds,
    drawdownSeconds: scale.liftSeconds != null && lastPourEnd >= 0
      ? Math.max(0, scale.liftSeconds - lastPourEnd)
      : null,
    peakFlowGramsPerSecond: pouring.length ? Math.max(...pouring) : null,
    averagePourFlowGramsPerSecond: pouring.length ? round1(pouring.reduce((a, b) => a + b, 0) / pouring.length) : null,
    secondsWithoutScale: scale.curve.filter((grams) => grams == null).length,
    // Every fifth second keeps the curve's shape at a fraction of its size.
    weightEvery5Seconds: scale.curve.filter((_, second) => second % 5 === 0),
  };
}
