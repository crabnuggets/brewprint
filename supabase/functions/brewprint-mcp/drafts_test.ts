import { assertEquals } from "@std/assert";
import type { Beans, BrewRecord, Journal, Recipe } from "./brews.ts";
import { buildBeans, buildJournal, buildRecipe, EMPTY_JOURNAL, recipeErrors, webAddress } from "./drafts.ts";

// The app's McpContractTest reads these fixtures as Beans and Recipe, so a draft built here is one the app can add.
const fixture = async <T>(name: string): Promise<T> =>
  JSON.parse(await Deno.readTextFile(new URL(`./fixtures/${name}`, import.meta.url)));

Deno.test("beans drafts are built exactly as the app saves beans", async () => {
  const built = buildBeans(
    "draft-beans-1",
    {
      name: " Kii ",
      roaster: "Tim Wendelboe",
      origin: "Kenya",
      variety: "SL28, SL34",
      processing: "Washed",
      expectedNotes: "Blackcurrant, hibiscus",
      notes: "https://timwendelboe.no/product/kii",
      roastDate: "2026-11-10",
    },
    undefined,
    { batchId: "draft-batch-1", now: 1795400000000 },
  );
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

Deno.test("a roast date redates the draft's one batch, and an empty one clears its batches", async () => {
  const existing = await fixture<Beans>("draft-beans.json");
  assertEquals(buildBeans(existing.id, { roastDate: "2026-11-12" }, existing).item?.batches, [
    { ...existing.batches![0], roastDate: "2026-11-12" },
  ]);
  assertEquals(buildBeans(existing.id, { roastDate: "" }, existing).item?.batches, []);
  assertEquals(buildBeans("x", { name: "Kii", roaster: "Tim Wendelboe" }).item?.batches, []);
  for (const date of ["2026-02-30", "2026-13-01", "2026-00-10", "2026-01-32", "10 Nov"]) {
    assertEquals(buildBeans("x", { name: "Kii", roaster: "Tim Wendelboe", roastDate: date }).errors, [
      "Give the roast date as YYYY-MM-DD.",
    ]);
  }
});

Deno.test("recipe drafts are built exactly as the app saves recipes, with no wait after the last pour", async () => {
  const built = buildRecipe("draft-recipe-1", {
    name: "Three pours",
    dose: 15,
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
  // A draft stores no ratio: the app works it out from the dose and the pours.
  assertEquals("ratio" in halved.item!, false);
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

Deno.test("an iced recipe keeps its ice, and a hot one has none", () => {
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
  assertEquals(iced.iceGrams, 70);
  // A hot recipe keeps no ice, and ice given for one is a mistake to fix.
  assertEquals(buildRecipe(iced.id, { drinkStyle: "HOT" }, iced).item?.iceGrams, null);
  assertEquals(buildRecipe(iced.id, { drinkStyle: "HOT", iceGrams: 70 }, iced).errors, [
    'Ice is only for an iced recipe: give drinkStyle "ICED".',
  ]);
  assertEquals(buildRecipe(iced.id, { notes: null }, { ...iced, notes: "Swirl after the bloom." }).item?.notes, "");
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
  assertEquals(
    recipeErrors(recipe({ pulses: [{ label: "", water: 240.05, pourSeconds: 0, waitSeconds: 0, notes: "" }] })),
    ["Pulse 1: water must be positive with up to one decimal place.", "Pulse 1: pour duration must be positive."],
  );
});

Deno.test("tasting notes are drafted onto the brew's own notes, exactly as the app saves a journal", async () => {
  const brew = await fixture<BrewRecord>("brew-record.json");
  const built = buildJournal(
    {
      aroma: " Like blackcurrant jam ",
      flavorTags: ["Fruity", "Floral", "Fruity"],
      flavor: "Blackcurrant, then grapefruit",
      mouthfeelTags: ["Silky"],
      finish: "Tea-like",
      finishLength: "Medium",
      asItCools: "Sweeter as it cooled",
    },
    brew.journal,
    brew.journal,
  );
  assertEquals(built.item, await fixture<Journal>("draft-journal.json"));
});

Deno.test("drafting tasting notes again keeps the waiting draft's fields, and null clears one", async () => {
  const brew = await fixture<BrewRecord>("brew-record.json");
  const waiting = await fixture<Journal>("draft-journal.json");
  const corrected = buildJournal({ body: "Medium", aroma: null, flavorTags: null }, waiting, brew.journal).item!;
  assertEquals(corrected, { ...waiting, body: "Medium", aroma: "", flavorTags: [] });
});

Deno.test("tasting notes that change nothing aren't drafted", async () => {
  const brew = await fixture<BrewRecord>("brew-record.json");
  const unchanged = [
    "These are already the brew's tasting notes. Give the fields that should change, or use discard_draft with the " +
    "brew's id to drop notes already waiting.",
  ];
  assertEquals(buildJournal({}, brew.journal, brew.journal).errors, unchanged);
  assertEquals(
    buildJournal({ impression: " Bright but a little thin " }, brew.journal, brew.journal).errors,
    unchanged,
  );
  // A brew saved before a journal field existed lacks it; the draft has every field.
  const { nextTime: _, ...older } = brew.journal;
  assertEquals(buildJournal({ nextTime: "Finer" }, older, older).item, { ...brew.journal, nextTime: "Finer" });
  assertEquals(buildJournal({ impression: "Sweet" }, {}, {}).item, { ...EMPTY_JOURNAL, impression: "Sweet" });
});
