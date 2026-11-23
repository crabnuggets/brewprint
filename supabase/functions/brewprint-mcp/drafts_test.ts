import { assertEquals } from "@std/assert";
import type { Beans, Recipe } from "./brews.ts";
import { buildBeans, buildRecipe, recipeErrors, totalBrewWater, webAddress } from "./drafts.ts";

// The app's McpContractTest reads these fixtures as Beans and Recipe, so a draft built here is one the app can add.
const fixture = async <T>(name: string): Promise<T> =>
  JSON.parse(await Deno.readTextFile(new URL(`./fixtures/${name}`, import.meta.url)));

Deno.test("beans drafts are built exactly as the app saves beans", async () => {
  const built = buildBeans("draft-beans-1", {
    name: " Kii ",
    roaster: "Tim Wendelboe",
    origin: "Kenya",
    variety: "SL28, SL34",
    processing: "Washed",
    expectedNotes: "Blackcurrant, hibiscus",
    notes: "https://timwendelboe.no/product/kii",
  });
  assertEquals(built.item, await fixture<Beans>("draft-beans.json"));
});

Deno.test("beans drafts need a name and roaster", () => {
  assertEquals(buildBeans("x", { name: "Kii", roaster: " " }).errors, ["Enter a roaster."]);
  assertEquals(buildBeans("x", {}).errors, ["Enter a name.", "Enter a roaster."]);
});

Deno.test("correcting a beans draft keeps the fields not given", async () => {
  const existing = await fixture<Beans>("draft-beans.json");
  const built = buildBeans(existing.id, { processing: "Natural" }, existing);
  assertEquals(built.item, { ...existing, processing: "Natural" });
});

Deno.test("recipe drafts are built exactly as the app saves recipes, with no wait after the last pour", async () => {
  const built = buildRecipe("draft-recipe-1", {
    name: "Three pours",
    dose: 15,
    ratio: 16,
    pulses: [
      { label: "Bloom", water: 45, pourSeconds: 10, waitSeconds: 35, notes: "Stir gently 3 times" },
      { water: 100, pourSeconds: 15, waitSeconds: 20 },
      { water: 95, pourSeconds: 15, waitSeconds: 30 },
    ],
    notes: "Swirl after the bloom.",
    waterTempC: 93,
    targetFinishSeconds: 210,
    grindDescription: "Medium-fine",
    sourceUrl: "https://example.com/three-pours",
  });
  assertEquals(built.item, await fixture<Recipe>("draft-recipe.json"));
});

Deno.test("correcting a recipe draft keeps the pulses unless new ones are given", async () => {
  const existing = await fixture<Recipe>("draft-recipe.json");
  assertEquals(buildRecipe(existing.id, { name: "Renamed" }, existing).item, { ...existing, name: "Renamed" });
  const halved = buildRecipe(existing.id, { dose: 7.5, pulses: [{ water: 120, pourSeconds: 30 }] }, existing);
  assertEquals(halved.item?.pulses, [{ label: "", water: 120, pourSeconds: 30, waitSeconds: 0, notes: "" }]);
  // The stored ratio follows the new dose and pours.
  assertEquals(halved.item?.ratio, 16);
});

Deno.test("a correction keeps details it doesn't mention and clears ones given as null", async () => {
  const existing = await fixture<Recipe>("draft-recipe.json");
  const kept = buildRecipe(existing.id, { name: "Renamed" }, existing).item!;
  assertEquals([kept.waterTempC, kept.targetFinishSeconds, kept.grindDescription], [93, 210, "Medium-fine"]);
  const cleared = buildRecipe(existing.id, { waterTempC: null, grindDescription: null, sourceUrl: null }, existing)
    .item!;
  assertEquals([cleared.waterTempC, cleared.grindDescription, cleared.sourceUrl], [null, "", ""]);
  assertEquals(cleared.targetFinishSeconds, 210);
});

Deno.test("the stored ratio comes from the dose and the pours, to 6 decimals like the app", () => {
  const iced = buildRecipe("x", {
    name: "Kurasu Iced",
    dose: 16,
    drinkStyle: "ICED",
    iceGrams: 70,
    pulses: [{ water: 40, pourSeconds: 10, waitSeconds: 35 }, { water: 60, pourSeconds: 15, waitSeconds: 15 }, {
      water: 50,
      pourSeconds: 15,
    }],
  }).item!;
  assertEquals([iced.ratio, iced.iceGrams], [9.375, 70]);
  assertEquals(
    buildRecipe("x", { name: "15 to 250", dose: 15, pulses: [{ water: 250, pourSeconds: 50 }] }).item?.ratio,
    16.666667,
  );
  // A hot recipe keeps no ice.
  assertEquals(buildRecipe(iced.id, { drinkStyle: "HOT" }, iced).item?.iceGrams, null);
});

Deno.test("only http and https addresses with a host are kept as a source", () => {
  assertEquals([webAddress("https://kurasu.kyoto/pages/iced"), webAddress("http://example.com")], [true, true]);
  assertEquals([webAddress("javascript:alert(1)"), webAddress("kurasu.kyoto"), webAddress("https://")], [
    false,
    false,
    false,
  ]);
});

Deno.test("a recipe draft the app would refuse is refused with the app's reasons", () => {
  const recipe = (changes: Partial<Recipe>): Recipe => ({
    id: "x",
    name: "Plan",
    dose: 15,
    ratio: 16,
    pulses: [{ label: "", water: 240, pourSeconds: 10, waitSeconds: 0, notes: "" }],
    notes: "",
    archived: false,
    ...changes,
  });
  assertEquals(recipeErrors(recipe({})), []);
  // The ratio follows the dose, so a new dose alone is valid.
  assertEquals(recipeErrors(recipe({ dose: 16 })), []);
  assertEquals(recipeErrors(recipe({ dose: 15.01 }))[0], "Coffee dose must be positive with up to one decimal place.");
  assertEquals(recipeErrors(recipe({ name: " " })), ["Enter a recipe name."]);
  assertEquals(recipeErrors(recipe({ pulses: [] })), ["Add at least one pulse."]);
  assertEquals(recipeErrors(recipe({ drinkStyle: "ICED", iceGrams: 0 })), [
    "Ice must be positive with up to one decimal place.",
  ]);
  assertEquals(recipeErrors(recipe({ waterTempC: 101 })), ["Water temperature must be from 1 to 100 °C."]);
  assertEquals(recipeErrors(recipe({ sourceUrl: "ftp://example.com" })), [
    "Enter a web address starting with http:// or https://.",
  ]);
  // A ratio a prompt still sends must match the pours.
  const plan = { name: "Plan", dose: 16, pulses: [{ water: 240, pourSeconds: 10 }] };
  assertEquals(buildRecipe("x", { ...plan, ratio: 16 }).errors, ["Add 16 g across your pulses to reach 256 g."]);
  assertEquals(buildRecipe("x", { ...plan, ratio: 0 }).errors, ["Enter a positive brew ratio."]);
  assertEquals(buildRecipe("x", { ...plan, ratio: 15 }).errors, []);
  assertEquals(
    recipeErrors(recipe({ pulses: [{ label: "", water: 240.05, pourSeconds: 0, waitSeconds: 0, notes: "" }] })),
    ["Pulse 1: water must be positive with up to one decimal place.", "Pulse 1: pour duration must be positive."],
  );
});

Deno.test("total brew water rounds half up in decimal, as the app does", () => {
  // From the app's RecipeTest: binary multiplication would round these the other way.
  assertEquals(totalBrewWater(15.1, 16.5), 249.2);
  assertEquals(totalBrewWater(15.1, 15.5), 234.1);
  assertEquals(totalBrewWater(15, 16.667), 250);
  assertEquals(totalBrewWater(15, 16), 240);
  assertEquals(
    buildRecipe("x", { name: "15 to 250", dose: 15, ratio: 16.667, pulses: [{ water: 250, pourSeconds: 50 }] })
      .errors,
    [],
  );
});
