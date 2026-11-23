// Beans and recipes Claude drafts for the user to add in the app. A draft holds the item exactly as the app saves it
// (Models.kt), so adding it in the app needs no conversion. fixtures/draft-*.json are drafts as built here; a Kotlin
// test fails when the app can't read them.
import {
  type BeanBatch,
  type Beans,
  decimal,
  type DrinkStyle,
  type Pulse,
  ratioOf,
  type Recipe,
  totalWater,
} from "./brews.ts";

export type DraftKind = "beans" | "recipes";

export interface BeansInput {
  name?: string;
  roaster?: string;
  origin?: string;
  variety?: string;
  processing?: string;
  expectedNotes?: string;
  notes?: string;
  /** The bag's roast date, YYYY-MM-DD. Undefined keeps the draft's batches; "" clears them. */
  roastDate?: string;
}

/** Fixed values for tests: the new batch's id, and the time it was added. */
export interface BatchOptions {
  batchId?: string;
  now?: number;
}

export interface PulseInput {
  label?: string;
  water: number;
  pourSeconds: number;
  waitSeconds?: number;
  notes?: string;
}

/** For the optional details, undefined keeps what a corrected draft had and null clears it. */
export interface RecipeInput {
  name?: string;
  dose?: number;
  /** Only checked against the pours, for prompts that still send it; the stored ratio comes from the pours. */
  ratio?: number;
  pulses?: PulseInput[];
  notes?: string;
  drinkStyle?: DrinkStyle;
  iceGrams?: number | null;
  waterTempC?: number | null;
  targetFinishSeconds?: number | null;
  grindDescription?: string | null;
  sourceUrl?: string | null;
}

export type Built<T> = { item: T; errors: [] } | { item: null; errors: string[] };

const text = (value: string | null | undefined, fallback = "") => (value === undefined ? fallback : value ?? "").trim();
const given = <T>(value: T | null | undefined, existing: T | null | undefined): T | null =>
  value === undefined ? existing ?? null : value;

/**
 * New beans from [input], or [existing] with the fields [input] gives replaced. The app requires a name and roaster.
 * A roast date gives the beans one batch, or redates the one they have, as the app's beans form does.
 */
export function buildBeans(id: string, input: BeansInput, existing?: Beans, options: BatchOptions = {}): Built<Beans> {
  const roastDate = input.roastDate?.trim();
  const kept = existing?.batches ?? [];
  const batches: BeanBatch[] = roastDate === undefined
    ? kept
    : roastDate === ""
    ? []
    : kept.length === 1
    ? [{ ...kept[0], roastDate }]
    : [{ id: options.batchId ?? crypto.randomUUID(), roastDate, notes: "", addedAt: options.now ?? Date.now() }];
  const beans: Beans = {
    id,
    name: text(input.name, existing?.name),
    roaster: text(input.roaster, existing?.roaster),
    origin: text(input.origin, existing?.origin),
    variety: text(input.variety, existing?.variety),
    processing: text(input.processing, existing?.processing),
    expectedNotes: text(input.expectedNotes, existing?.expectedNotes),
    notes: text(input.notes, existing?.notes),
    archived: false,
    batches,
  };
  const errors = [
    !beans.name && "Enter a name.",
    !beans.roaster && "Enter a roaster.",
    roastDate && !isoDate(roastDate) && "Give the roast date as YYYY-MM-DD.",
  ].filter((error): error is string => !!error);
  return errors.length ? { item: null, errors } : { item: beans, errors: [] };
}

/**
 * A new recipe from [input], or [existing] with the fields [input] gives replaced; pulses are replaced as a whole.
 * The stored ratio always comes from the dose and the pours, as the app's Recipe.normalized() does.
 */
export function buildRecipe(id: string, input: RecipeInput, existing?: Recipe): Built<Recipe> {
  const pulses: Pulse[] = input.pulses
    ? input.pulses.map((pulse, index, all) => ({
      label: text(pulse.label),
      water: pulse.water,
      pourSeconds: pulse.pourSeconds,
      // Nothing follows the last pour, so the app keeps no wait after it.
      waitSeconds: index === all.length - 1 ? 0 : pulse.waitSeconds ?? 0,
      notes: text(pulse.notes),
    }))
    : (existing?.pulses ?? []).map((pulse) => ({ ...pulse, notes: pulse.notes ?? "" }));
  const drinkStyle = input.drinkStyle ?? existing?.drinkStyle ?? "HOT";
  const dose = input.dose ?? existing?.dose ?? 0;
  const recipe: Recipe = {
    id,
    name: text(input.name, existing?.name),
    dose,
    ratio: ratioOf(dose, totalWater(pulses), 6),
    pulses,
    notes: text(input.notes, existing?.notes),
    archived: false,
    drinkStyle,
    // A hot recipe has no ice.
    iceGrams: drinkStyle === "ICED" ? given(input.iceGrams, existing?.iceGrams) : null,
    waterTempC: given(input.waterTempC, existing?.waterTempC),
    targetFinishSeconds: given(input.targetFinishSeconds, existing?.targetFinishSeconds),
    grindDescription: text(input.grindDescription, existing?.grindDescription),
    sourceUrl: text(input.sourceUrl, existing?.sourceUrl),
  };
  const errors = [...recipeErrors(recipe), ...(input.ratio === undefined ? [] : ratioErrors(recipe, input.ratio))];
  return errors.length ? { item: null, errors } : { item: recipe, errors: [] };
}

/** The app's Recipe.validationErrors(), so a draft the app would refuse to save is refused here first. */
export function recipeErrors(recipe: Recipe): string[] {
  const errors: string[] = [];
  if (!recipe.name.trim()) errors.push("Enter a recipe name.");
  if (!(recipe.dose > 0) || !oneDecimal(recipe.dose)) {
    errors.push("Coffee dose must be positive with up to one decimal place.");
  }
  if (recipe.pulses.length === 0) errors.push("Add at least one pulse.");
  recipe.pulses.forEach((pulse, index) => {
    if (!(pulse.water > 0) || !oneDecimal(pulse.water)) {
      errors.push(`Pulse ${index + 1}: water must be positive with up to one decimal place.`);
    }
    if (!(pulse.pourSeconds > 0)) errors.push(`Pulse ${index + 1}: pour duration must be positive.`);
    if (pulse.waitSeconds < 0) errors.push(`Pulse ${index + 1}: wait cannot be negative.`);
  });
  const ice = recipe.iceGrams;
  if (recipe.drinkStyle === "ICED" && ice != null && (!(ice > 0) || !oneDecimal(ice))) {
    errors.push("Ice must be positive with up to one decimal place.");
  }
  const temp = recipe.waterTempC;
  if (temp != null && !(Number.isInteger(temp) && temp >= 1 && temp <= 100)) {
    errors.push("Water temperature must be from 1 to 100 °C.");
  }
  const target = recipe.targetFinishSeconds;
  if (target != null && !(Number.isInteger(target) && target > 0)) errors.push("Target finish time must be positive.");
  if (recipe.sourceUrl && !webAddress(recipe.sourceUrl)) {
    errors.push("Enter a web address starting with http:// or https://.");
  }
  return errors;
}

/** A ratio a prompt gave, which the pours must still match: dose × ratio, rounded half up to 0.1 g. */
function ratioErrors(recipe: Recipe, ratio: number): string[] {
  const total = totalBrewWater(recipe.dose, ratio);
  if (!(ratio > 0) || total == null || total <= 0) return ["Enter a positive brew ratio."];
  const planned = recipe.pulses.every((pulse) => oneDecimal(pulse.water)) ? totalWater(recipe.pulses) : null;
  if (planned == null || planned === total) return [];
  const difference = Math.round((total - planned) * 10) / 10;
  return [
    difference > 0
      ? `Add ${difference} g across your pulses to reach ${total} g.`
      : `Remove ${-difference} g across your pulses to reach ${total} g.`,
  ];
}

/** An http or https address with a host, the only kind the app opens. */
export function webAddress(value: string): boolean {
  try {
    const url = new URL(value.trim());
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname !== "";
  } catch {
    return false;
  }
}

/** A real calendar date written YYYY-MM-DD. */
function isoDate(value: string): boolean {
  const date = new Date(`${value}T00:00:00Z`);
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
}

const oneDecimal = (value: number) => Number.isFinite(value) && /^-?\d+(\.\d)?$/.test(String(value));

/** Dose × ratio rounded half up to 0.1 g, in decimal arithmetic, so a given ratio is checked as the app once did. */
export function totalBrewWater(dose: number, ratio: number): number | null {
  const a = decimal(dose);
  const b = decimal(ratio);
  if (!a || !b || dose <= 0 || ratio <= 0) return null;
  const product = a[0] * b[0];
  const scale = a[1] + b[1];
  if (scale <= 1) return Number(product) / 10 ** scale;
  const divisor = 10n ** BigInt(scale - 1);
  const tenths = product / divisor + (2n * (product % divisor) >= divisor ? 1n : 0n);
  return Number(tenths) / 10;
}
