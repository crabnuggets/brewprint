import { assertEquals } from "@std/assert";
import { brewDetail, type BrewRecord, brewSummary, findBrews, library } from "./brews.ts";

// The same brew the app's McpContractTest checks against the Kotlin model.
const brew: BrewRecord = JSON.parse(await Deno.readTextFile(new URL("./fixtures/brew-record.json", import.meta.url)));

Deno.test("rows sort into the library, newest brew first", () => {
  const older = { ...brew, id: "older", startedAt: brew.startedAt - 1000 };
  const shelf = library([
    { kind: "brews", data: older },
    { kind: "brews", data: brew },
    { kind: "beans", data: brew.beans },
    { kind: "grinders", data: brew.grinder },
    { kind: "recipes", data: brew.recipe },
  ]);
  assertEquals(shelf.brews.map((item) => item.id), [brew.id, "older"]);
  assertEquals([shelf.beans.length, shelf.grinders.length, shelf.recipes.length], [1, 1, 1]);
});

Deno.test("brews filter by beans, recipe and inclusive days", () => {
  const ids = (filter: Parameters<typeof findBrews>[1]) => findBrews([brew], filter).map((item) => item.id);
  assertEquals(ids({ beans: "wendelboe" }), [brew.id]);
  assertEquals(ids({ beans: "geisha" }), []);
  assertEquals(ids({ recipe: "three" }), [brew.id]);
  // The fixture was brewed on 2025-11-21 (UTC).
  assertEquals(ids({ since: "2025-11-21", until: "2025-11-21" }), [brew.id]);
  assertEquals(ids({ until: "2025-11-20" }), []);
  assertEquals(ids({ since: "2025-11-22" }), []);
});

Deno.test("a summary keeps only the tasting notes the user filled in", () => {
  const summary = brewSummary(brew);
  assertEquals(summary.ratio, "1:16");
  assertEquals(summary.totalBrewWaterGrams, 240);
  assertEquals(summary.measuredWaterGrams, 238.6);
  assertEquals(summary.tasting, {
    impression: "Bright but a little thin",
    flavorTags: ["Fruity"],
    flavor: "Blackcurrant",
    sweetness: "Medium",
    acidity: "High",
    acidityNotes: "Sharp at first",
    body: "Light",
    finishLength: "Short",
    nextTime: "Grind two ticks finer",
  });
});

Deno.test("a brew's pulses set the plan beside what happened", () => {
  assertEquals(
    brewDetail(brew).pulses.map((pulse) => [
      pulse.label,
      pulse.plannedStartSeconds,
      pulse.actualStartSeconds,
      pulse.cumulativeTargetGrams,
      pulse.reachedGrams,
    ]),
    [["Bloom", 0, 0, 45, 45.2], ["Pulse 2", 45, 46, 145, 145], ["Pulse 3", 80, 82, 240, null]],
  );
});

Deno.test("the scale curve becomes flow, drawdown and a coarse curve", () => {
  const scale = brewDetail(brew).scale!;
  // The last pour started at 82 s and lasted 15 s; the dripper came off at 170 s.
  assertEquals(scale.drawdownSeconds, 73);
  assertEquals(scale.peakFlowGramsPerSecond, 6.7);
  assertEquals(scale.secondsWithoutScale, 2);
  assertEquals(scale.weightEvery5Seconds.length, 35);
  assertEquals(scale.weightEvery5Seconds[12], null);
});

Deno.test("a brew without a scale or timer has no analysis", () => {
  const detail = brewDetail({ ...brew, scale: null, timing: null });
  assertEquals([detail.scale, detail.timing, detail.totalBrewTimeSeconds], [null, null, null]);
  assertEquals(detail.pulses.map((pulse) => pulse.actualStartSeconds), [null, null, null]);
});
