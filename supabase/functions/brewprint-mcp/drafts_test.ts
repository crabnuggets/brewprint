import { assertEquals } from "@std/assert";
import type { Beans, Recipe } from "./brews.ts";
import { buildBeans, buildRecipe, recipeErrors, totalBrewWater } from "./drafts.ts";

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
      { label: "Bloom", water: 45, pourSeconds: 10, waitSeconds: 35 },
      { water: 100, pourSeconds: 15, waitSeconds: 20 },
      { water: 95, pourSeconds: 15, waitSeconds: 30 },
    ],
    notes: "Swirl after the bloom.",
  });
  assertEquals(built.item, await fixture<Recipe>("draft-recipe.json"));
});

Deno.test("correcting a recipe draft keeps the pulses unless new ones are given", async () => {
  const existing = await fixture<Recipe>("draft-recipe.json");
  assertEquals(buildRecipe(existing.id, { name: "Renamed" }, existing).item, { ...existing, name: "Renamed" });
  const halved = buildRecipe(existing.id, { dose: 7.5, pulses: [{ water: 120, pourSeconds: 30 }] }, existing);
  assertEquals(halved.item?.pulses, [{ label: "", water: 120, pourSeconds: 30, waitSeconds: 0 }]);
});

Deno.test("a recipe draft the app would refuse is refused with the app's reasons", () => {
  const recipe = (changes: Partial<Recipe>): Recipe => ({
    id: "x",
    name: "Plan",
    dose: 15,
    ratio: 16,
    pulses: [{ label: "", water: 240, pourSeconds: 10, waitSeconds: 0 }],
    notes: "",
    archived: false,
    ...changes,
  });
  assertEquals(recipeErrors(recipe({})), []);
  assertEquals(recipeErrors(recipe({ dose: 16 })), ["Add 16 g across your pulses to reach 256 g."]);
  assertEquals(recipeErrors(recipe({ dose: 14 })), ["Remove 16 g across your pulses to reach 224 g."]);
  assertEquals(recipeErrors(recipe({ dose: 15.01 }))[0], "Coffee dose must be positive with up to one decimal place.");
  assertEquals(recipeErrors(recipe({ name: " " })), ["Enter a recipe name."]);
  assertEquals(recipeErrors(recipe({ ratio: 0 })), ["Enter a positive brew ratio."]);
  assertEquals(recipeErrors(recipe({ pulses: [] })), [
    "Add at least one pulse.",
    "Add 240 g across your pulses to reach 240 g.",
  ]);
  assertEquals(
    recipeErrors(recipe({ pulses: [{ label: "", water: 240.05, pourSeconds: 0, waitSeconds: 0 }] })),
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
