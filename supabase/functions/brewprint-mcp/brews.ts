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
  /** Missing on records saved before step notes. */
  notes?: string;
}

export type DrinkStyle = "HOT" | "ICED";

export interface Recipe {
  id: string;
  name: string;
  dose: number;
  /** Older app versions check this against the pours; read the ratio from dose and pulses instead (recipeRatio). */
  ratio: number;
  pulses: Pulse[];
  notes: string;
  archived: boolean;
  // Missing on recipes saved before these details existed.
  drinkStyle?: DrinkStyle;
  iceGrams?: number | null;
  waterTempC?: number | null;
  targetFinishSeconds?: number | null;
  grindDescription?: string;
  sourceUrl?: string;
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

/** A number's exact decimal digits and scale, as the app's BigDecimal.valueOf reads it. Null for exponent notation. */
export function decimal(value: number): [bigint, number] | null {
  const written = String(value);
  if (!Number.isFinite(value) || !/^-?\d+(\.\d+)?$/.test(written)) return null;
  const [whole, fraction = ""] = written.split(".");
  return [BigInt(whole + fraction), fraction.length];
}

/** The hot water poured: the pulses' total, in whole tenths of a gram as the app adds it. */
export function totalWater(pulses: Pulse[]): number {
  return pulses.reduce((tenths, pulse) => tenths + Math.round(pulse.water * 10), 0) / 10;
}

/**
 * [grams] per gram of [dose], rounded half up to [places] decimals in decimal arithmetic, as the app's BigDecimal
 * does: 6 places for the ratio the app stores, 1 for the ratio people read. 0 when either is missing.
 */
export function ratioOf(dose: number, grams: number, places: number): number {
  const d = decimal(dose);
  const tenths = Math.round(grams * 10);
  if (!d || dose <= 0 || tenths <= 0) return 0;
  const numerator = BigInt(tenths) * 10n ** BigInt(d[1] + places);
  const denominator = d[0] * 10n;
  const quotient = numerator / denominator + (2n * (numerator % denominator) >= denominator ? 1n : 0n);
  return Number(quotient) / 10 ** places;
}

/** The ratio as people read it, from the dose and the pours: 1:9.4, 1:16. */
export const recipeRatio = (recipe: Recipe) => ratioOf(recipe.dose, totalWater(recipe.pulses), 1);

/** For an iced recipe with its ice given, water and ice together per gram of coffee, to one decimal. */
export function overallRatio(recipe: Recipe): number | null {
  if (recipe.drinkStyle !== "ICED" || !(recipe.iceGrams != null && recipe.iceGrams > 0)) return null;
  return ratioOf(recipe.dose, totalWater(recipe.pulses) + recipe.iceGrams, 1);
}

/** The recipe's style and the details it gives; ones it leaves out are null. */
function recipeDetails(recipe: Recipe) {
  const iced = recipe.drinkStyle === "ICED";
  return {
    style: iced ? "iced" : "hot",
    iceGrams: iced ? recipe.iceGrams ?? null : null,
    overallRatio: overallRatio(recipe) == null ? null : `1:${overallRatio(recipe)}`,
    waterTempC: recipe.waterTempC ?? null,
    targetFinishSeconds: recipe.targetFinishSeconds ?? null,
    grindSize: recipe.grindDescription || null,
    sourceUrl: recipe.sourceUrl || null,
  };
}

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
    ratio: `1:${recipeRatio(brew.recipe)}`,
    totalBrewWaterGrams: totalWater(brew.recipe.pulses),
    style: recipeDetails(brew.recipe).style,
    iceGrams: recipeDetails(brew.recipe).iceGrams,
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
      note: pulse.notes || null,
      plannedStartSeconds: plannedStart,
      actualStartSeconds: timing?.pulseStarts[index] ?? null,
      reachedGrams: scale?.reached[index] ?? null,
    };
  });
  return {
    ...brewSummary(brew),
    beans: brew.beans,
    grinder: { name: brew.grinder.name, referenceNotes: brew.grinder.referenceNotes },
    recipe: {
      name: recipe.name,
      dose: recipe.dose,
      ratio: `1:${recipeRatio(recipe)}`,
      notes: recipe.notes,
      ...recipeDetails(recipe),
    },
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
  // The app keeps recording after the dripper comes off; putting it back down would read as another pour.
  const brewing = scale.liftSeconds != null ? scale.curve.slice(0, scale.liftSeconds) : scale.curve;
  const flows = brewing.map((grams, second) => {
    const before = brewing[second - 1];
    // As in the app's ScaleData.flows(): below 0 g the brewer was off the scale, so that second is a gap.
    return second > 0 && grams != null && before != null && grams >= 0 && before >= 0
      ? round1(Math.max(0, grams - before))
      : null;
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
