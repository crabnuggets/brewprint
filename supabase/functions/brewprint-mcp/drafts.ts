// Beans and recipes Claude drafts for the user to add in the app. A draft holds the item exactly as the app saves it
// (Models.kt), so adding it in the app needs no conversion. fixtures/draft-*.json are drafts as built here; a Kotlin
// test fails when the app can't read them.
import type { Beans, Pulse, Recipe } from "./brews.ts";

export type DraftKind = "beans" | "recipes";

export interface BeansInput {
  name?: string;
  roaster?: string;
  origin?: string;
  variety?: string;
  processing?: string;
  expectedNotes?: string;
  notes?: string;
}

export interface PulseInput {
  label?: string;
  water: number;
  pourSeconds: number;
  waitSeconds?: number;
}

export interface RecipeInput {
  name?: string;
  dose?: number;
  ratio?: number;
  pulses?: PulseInput[];
  notes?: string;
}

export type Built<T> = { item: T; errors: [] } | { item: null; errors: string[] };

const text = (value: string | undefined, fallback = "") => (value ?? fallback).trim();

/** New beans from [input], or [existing] with the fields [input] gives replaced. The app requires a name and roaster. */
export function buildBeans(id: string, input: BeansInput, existing?: Beans): Built<Beans> {
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
  };
  const errors = [
    !beans.name && "Enter a name.",
    !beans.roaster && "Enter a roaster.",
  ].filter((error): error is string => !!error);
  return errors.length ? { item: null, errors } : { item: beans, errors: [] };
}

/** A new recipe from [input], or [existing] with the fields [input] gives replaced; pulses are replaced as a whole. */
export function buildRecipe(id: string, input: RecipeInput, existing?: Recipe): Built<Recipe> {
  const pulses: Pulse[] = input.pulses
    ? input.pulses.map((pulse, index, all) => ({
      label: text(pulse.label),
      water: pulse.water,
      pourSeconds: pulse.pourSeconds,
      // Nothing follows the last pour, so the app keeps no wait after it.
      waitSeconds: index === all.length - 1 ? 0 : pulse.waitSeconds ?? 0,
    }))
    : existing?.pulses ?? [];
  const recipe: Recipe = {
    id,
    name: text(input.name, existing?.name),
    dose: input.dose ?? existing?.dose ?? 0,
    ratio: input.ratio ?? existing?.ratio ?? 0,
    pulses,
    notes: text(input.notes, existing?.notes),
    archived: false,
  };
  const errors = recipeErrors(recipe);
  return errors.length ? { item: null, errors } : { item: recipe, errors: [] };
}

/** The app's Recipe.validationErrors(), so a draft the app would refuse to save is refused here first. */
export function recipeErrors(recipe: Recipe): string[] {
  const errors: string[] = [];
  if (!recipe.name.trim()) errors.push("Enter a recipe name.");
  if (!(recipe.dose > 0) || !oneDecimal(recipe.dose)) {
    errors.push("Coffee dose must be positive with up to one decimal place.");
  }
  const total = totalBrewWater(recipe.dose, recipe.ratio);
  if (!(recipe.ratio > 0) || total == null || total <= 0) errors.push("Enter a positive brew ratio.");
  if (recipe.pulses.length === 0) errors.push("Add at least one pulse.");
  recipe.pulses.forEach((pulse, index) => {
    if (!(pulse.water > 0) || !oneDecimal(pulse.water)) {
      errors.push(`Pulse ${index + 1}: water must be positive with up to one decimal place.`);
    }
    if (!(pulse.pourSeconds > 0)) errors.push(`Pulse ${index + 1}: pour duration must be positive.`);
    if (pulse.waitSeconds < 0) errors.push(`Pulse ${index + 1}: wait cannot be negative.`);
  });
  const planned = recipe.pulses.every((pulse) => oneDecimal(pulse.water))
    ? recipe.pulses.reduce((tenths, pulse) => tenths + Math.round(pulse.water * 10), 0) / 10
    : null;
  if (planned != null && total != null && total > 0 && planned !== total) {
    const difference = Math.round((total - planned) * 10) / 10;
    errors.push(
      difference > 0
        ? `Add ${difference} g across your pulses to reach ${total} g.`
        : `Remove ${-difference} g across your pulses to reach ${total} g.`,
    );
  }
  return errors;
}

const oneDecimal = (value: number) => Number.isFinite(value) && /^-?\d+(\.\d)?$/.test(String(value));

/** A number's exact decimal digits and scale, as the app's BigDecimal.valueOf reads it. Null for exponent notation. */
function decimal(value: number): [bigint, number] | null {
  const written = String(value);
  if (!Number.isFinite(value) || !/^-?\d+(\.\d+)?$/.test(written)) return null;
  const [whole, fraction = ""] = written.split(".");
  return [BigInt(whole + fraction), fraction.length];
}

/** Dose × ratio rounded half up to 0.1 g, in decimal arithmetic like the app's totalBrewWater, so both agree exactly. */
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
