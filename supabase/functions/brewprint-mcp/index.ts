// Brewprint's MCP server: lets Claude read the signed-in user's beans, grinders, recipes and brews, and draft beans and
// recipes for the user to add in the app. Claude signs in through Supabase Auth's OAuth server; row-level security
// keeps it to that user's rows, lets it only read records (20261121090000_read_only_oauth_clients.sql) and lets it
// write drafts (20261121120000_drafts.sql).
import "@supabase/functions-js/edge-runtime.d.ts";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { z } from "zod";
import {
  type Beans,
  brewDetail,
  type BrewFilter,
  brewSummary,
  findBrews,
  library,
  type Recipe,
  recipeRatio,
  type Row,
} from "./brews.ts";
import { type BeansInput, buildBeans, buildRecipe, type Built, type DraftKind, type RecipeInput } from "./drafts.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
// Inside the local stack SUPABASE_URL is an internal address, so links handed to clients can come from here instead.
const PUBLIC_URL = (Deno.env.get("BREWPRINT_PUBLIC_URL") ?? SUPABASE_URL).replace(/\/$/, "");
const API_KEY = Deno.env.get("SUPABASE_ANON_KEY") ??
  Object.values(JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") ?? "{}"))[0] as string;
const RESOURCE = `${PUBLIC_URL}/functions/v1/brewprint-mcp`;
const RESOURCE_METADATA = `${RESOURCE}/.well-known/oauth-protected-resource`;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, Mcp-Session-Id, Mcp-Protocol-Version",
  "Access-Control-Expose-Headers": "WWW-Authenticate, Mcp-Session-Id",
};

const INSTRUCTIONS =
  `Brewprint is the user's filter coffee journal. These tools read it, and can draft new beans and recipes;
nothing here can change the user's library or brews. A draft waits in the app's Library until the user adds it,
after editing it if they like, or discards it. Correct a draft with its id rather than drafting it again.
A brew record keeps the beans, grinder, grind setting and recipe as they were on that day, plus the timing,
scale readings and the user's tasting journal. Grind settings are ticks from each grinder's own reference
point, so compare them only within one grinder. Times are UTC; the user's app shows their local time. Measured water is the scale's reading after the last pour,
not the weight of the drink. A recipe's ratio comes from its dose and pours. Recipes may be iced, with ice in the
server that isn't part of the brew water, and may give water temperature, a target finish time, a grind size and a
note on each pour. Beans keep each bag bought as a batch with its roast date, and a brew gives the roast date of
the bag it used; count days off roast in the user's local time zone. The app records no overall score; ask the user
when it matters.`;

function unauthorized(error?: string) {
  const challenge = [`Bearer resource_metadata="${RESOURCE_METADATA}"`, error && `error="${error}"`].filter(Boolean)
    .join(", ");
  return new Response(JSON.stringify({ error: error ?? "unauthorized" }), {
    status: 401,
    headers: { ...CORS, "Content-Type": "application/json", "WWW-Authenticate": challenge },
  });
}

/** A client that acts as the token's user, so row-level security decides what it sees. Null when the token is not valid. */
async function userClient(request: Request): Promise<SupabaseClient | null> {
  const token = request.headers.get("Authorization")?.match(/^Bearer\s+(\S+)$/i)?.[1];
  if (!token) return null;
  const client = createClient(SUPABASE_URL, API_KEY, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await client.auth.getClaims(token);
  return !error && data?.claims?.sub ? client : null;
}

async function readRows(client: SupabaseClient): Promise<Row[]> {
  const rows: Row[] = [];
  for (let from = 0;; from += 1000) {
    const { data, error } = await client.from("records").select("kind, id, data").eq("deleted", false)
      .order("kind").order("id").range(from, from + 999);
    if (error) throw new Error(`Couldn't read Brewprint: ${error.message}`);
    rows.push(...data);
    if (data.length < 1000) return rows;
  }
}

interface Draft {
  id: string;
  kind: DraftKind;
  data: Beans | Recipe;
}

async function readDrafts(client: SupabaseClient): Promise<Draft[]> {
  const { data, error } = await client.from("drafts").select("id, kind, data").order("created_at");
  if (error) throw new Error(`Couldn't read drafts: ${error.message}`);
  return data;
}

const text = (value: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(value, null, 1) }] });
const failure = (message: string) => ({ ...text({ error: message }), isError: true });

const WAITING = "Waiting in the Brewprint Library. The user adds it, after editing it if they like, or discards it.";
const GONE = "isn't waiting any more: the user may have added or discarded it. get_library shows what's there.";

/**
 * Drafts a new item when [draftId] is absent, or corrects that draft, keeping the fields [build] isn't given.
 * The app refuses invalid items, so the draft is only saved once [build] finds nothing to fix.
 */
async function saveDraft<T extends Beans | Recipe>(
  client: SupabaseClient,
  kind: DraftKind,
  draftId: string | undefined,
  build: (id: string, existing?: T) => Built<T>,
) {
  let existing: T | undefined;
  if (draftId) {
    const { data, error } = await client.from("drafts").select("data").eq("id", draftId).eq("kind", kind)
      .maybeSingle();
    if (error) throw new Error(`Couldn't read the draft: ${error.message}`);
    if (!data) return failure(`Draft ${draftId} ${GONE}`);
    existing = data.data as T;
  }
  const built = build(draftId ?? crypto.randomUUID(), existing);
  if (!built.item) return failure(`Nothing was saved. Fix these first: ${built.errors.join(" ")}`);
  const item = built.item;
  if (draftId) {
    const { data, error } = await client.from("drafts").update({ data: item }).eq("id", draftId).select("id");
    if (error) throw new Error(`Couldn't save the draft: ${error.message}`);
    if (!data.length) return failure(`Draft ${draftId} ${GONE}`);
  } else {
    const { error } = await client.from("drafts").insert({ id: item.id, kind, data: item });
    if (error) throw new Error(`Couldn't save the draft: ${error.message}`);
  }
  return text({ draftId: item.id, status: WAITING, draft: item });
}

function server(client: SupabaseClient) {
  const mcp = new McpServer({ name: "brewprint", version: "0.1.0" }, { instructions: INSTRUCTIONS });
  const read = { readOnlyHint: true, openWorldHint: false };

  mcp.registerTool("get_library", {
    title: "Beans, grinders and recipes",
    description: "The user's beans (with roaster, origin, process and expected tasting notes), grinders and recipes, " +
      "plus how many brews each beans and recipe has, and the drafts waiting for the user to add or discard. " +
      "Archived items are left out unless asked for.",
    inputSchema: { includeArchived: z.boolean().optional().describe("Include archived beans, grinders and recipes.") },
    annotations: read,
  }, async ({ includeArchived }: { includeArchived?: boolean }) => {
    const [rows, drafts] = await Promise.all([readRows(client), readDrafts(client)]);
    const { beans, grinders, recipes, brews } = library(rows);
    const shown = <T extends { archived: boolean }>(items: T[]) =>
      items.filter((item) => includeArchived || !item.archived);
    return text({
      beans: shown(beans).map((item) => ({ ...item, brews: brews.filter((brew) => brew.beans.id === item.id).length })),
      grinders: shown(grinders),
      recipes: shown(recipes).map((item) => ({
        ...item,
        // The ratio as the app shows it, from the dose and the pours.
        ratio: `1:${recipeRatio(item)}`,
        brews: brews.filter((brew) => brew.recipe.id === item.id).length,
      })),
      totalBrews: brews.length,
      drafts: {
        beans: drafts.filter((draft) => draft.kind === "beans").map((draft) => draft.data),
        recipes: drafts.filter((draft) => draft.kind === "recipes").map((draft) => ({
          ...(draft.data as Recipe),
          ratio: `1:${recipeRatio(draft.data as Recipe)}`,
        })),
      },
    });
  });

  mcp.registerTool("list_brews", {
    title: "List brews",
    description: "Brew records, newest first, each with its beans, grind, recipe, timing and tasting notes in brief. " +
      "Use get_brew for a brew's pulses and scale readings.",
    inputSchema: {
      beans: z.string().optional().describe("Part of the beans' name or roaster."),
      recipe: z.string().optional().describe("Part of the recipe's name."),
      since: z.string().optional().describe(
        "Earliest time, inclusive. A date-time with the user's offset, such as 2025-11-21T00:00:00+08:00, " +
          "or YYYY-MM-DD for a whole day in UTC.",
      ),
      until: z.string().optional().describe("Latest time, inclusive, in the same forms as since."),
      limit: z.number().int().min(1).max(200).optional().describe("At most this many brews. Default 20."),
    },
    annotations: read,
  }, async (filter: BrewFilter) => {
    const { brews } = library(await readRows(client));
    return text(findBrews(brews, filter).map(brewSummary));
  });

  mcp.registerTool("get_brew", {
    title: "Get a brew",
    description: "One brew in full: beans, grinder, recipe, each pulse's plan against what happened, " +
      "scale analysis (flow, drawdown, measured water, a coarse weight curve) and the tasting journal.",
    inputSchema: { id: z.string().describe("The brew's id from list_brews.") },
    annotations: read,
  }, async ({ id }: { id: string }) => {
    const brew = library(await readRows(client)).brews.find((item) => item.id === id);
    if (!brew) return { ...text({ error: `No brew with id ${id}.` }), isError: true };
    return text(brewDetail(brew));
  });

  const write = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false };
  const draftId = z.string().optional().describe(
    "The id of a draft to correct, from an earlier result or get_library's drafts. Leave out to start a new draft.",
  );
  const field = (description: string) => z.string().optional().describe(description);

  mcp.registerTool(
    "draft_beans",
    {
      title: "Draft beans",
      description: "Drafts beans for the user to add to their Brewprint library, or corrects a draft with draftId, " +
        "changing only the fields given. Check get_library with includeArchived first so the same beans aren't " +
        "drafted twice: when the beans are already in the library, archived or not, don't draft them again, and tell " +
        'the user to tap "Bought again" on them in the app (under "Archived beans" if they are archived) to add the ' +
        "new bag with its roast date. " +
        "A new draft needs a name and roaster; leave out what the source doesn't say rather than guessing.",
      inputSchema: {
        draftId,
        name: field('What the roaster calls the coffee, such as "Kii" or "Ethiopia natural".'),
        roaster: field("The roaster's name."),
        origin: field("Country, region or farm. A blend may list several."),
        variety: field('Variety or cultivar, such as "SL28, SL34".'),
        processing: field("Processing method, such as washed, natural or honey."),
        expectedNotes: field('The roaster\'s tasting notes, comma-separated, such as "Blackcurrant, hibiscus".'),
        notes: field(
          "Anything else worth keeping, such as altitude, roast level, harvest or the product page's address.",
        ),
        roastDate: field('The bag\'s roast date as YYYY-MM-DD, when it gives one. "" clears it on a correction.'),
      },
      annotations: write,
    },
    ({ draftId, ...input }: BeansInput & { draftId?: string }) =>
      saveDraft<Beans>(client, "beans", draftId, (id, existing) => buildBeans(id, input, existing)),
  );

  mcp.registerTool(
    "draft_recipe",
    {
      title: "Draft a recipe",
      description:
        "Drafts a recipe for the user to add to their Brewprint library, or corrects a draft with draftId, " +
        "changing only the fields given (pulses are replaced as a whole; null clears an optional detail). Check " +
        "get_library first so the same recipe isn't drafted twice. Dose and each pulse's water take up to one " +
        "decimal place. There is no ratio to give: the app works it out from the dose and the pours, so when a " +
        "recipe gives a ratio, work out each pour's water from it. Correcting only the dose changes the ratio; " +
        "give new pulses too to keep it. For an iced recipe the ice is not part of the pours.",
      inputSchema: {
        draftId,
        name: field("The recipe's name, such as the author's name for it."),
        dose: z.number().optional().describe("Coffee dose in grams."),
        drinkStyle: z.enum(["HOT", "ICED"]).optional().describe("ICED for a recipe brewed onto ice; HOT otherwise."),
        iceGrams: z.number().nullable().optional().describe(
          "For an iced recipe, grams of ice put in the server before brewing, up to one decimal place.",
        ),
        waterTempC: z.number().int().nullable().optional().describe("Water temperature in °C, 1 to 100."),
        targetFinishSeconds: z.number().int().nullable().optional().describe(
          'When the drawdown should end, in seconds from the first pour: 130 for "about 2:10".',
        ),
        grindDescription: z.string().nullable().optional().describe(
          'How the grind should look, in the source\'s words, such as "Medium-fine".',
        ),
        sourceUrl: z.string().nullable().optional().describe("The recipe's web address, http or https."),
        pulses: z.array(z.object({
          label: z.string().optional().describe('Such as "Bloom"; leave out for a plain pour.'),
          water: z.number().describe("Grams of water this pour adds, not the running total."),
          pourSeconds: z.number().int().describe(
            "How long the pour takes. If the recipe doesn't say, estimate about 5 g per second.",
          ),
          waitSeconds: z.number().int().optional().describe(
            "From the end of this pour to the start of the next. Ignored on the last pulse.",
          ),
          notes: z.string().optional().describe(
            'What to do during this pour or the wait after it, such as "Stir gently 3 times".',
          ),
        })).optional().describe(
          "Every pour in order. When a recipe gives start times, a wait is the next pour's start minus this " +
            'pour\'s start and pourSeconds; when it gives running totals ("pour to 150 g"), water is the difference.',
        ),
        notes: z.string().nullable().optional().describe(
          "Preparation notes that fit no other field, such as the dripper, filter, or what to do after the drawdown.",
        ),
      },
      annotations: write,
    },
    ({ draftId, ...input }: RecipeInput & { draftId?: string }) =>
      saveDraft<Recipe>(client, "recipes", draftId, (id, existing) => buildRecipe(id, input, existing)),
  );

  mcp.registerTool("discard_draft", {
    title: "Discard a draft",
    description: "Removes a draft beans or recipe that the user no longer wants, such as a duplicate. " +
      "Items already in the library can't be changed here.",
    inputSchema: { draftId: z.string().describe("The draft's id.") },
    annotations: { ...write, destructiveHint: true },
  }, async ({ draftId }: { draftId: string }) => {
    const { data, error } = await client.from("drafts").delete().eq("id", draftId).select("id");
    if (error) throw new Error(`Couldn't discard the draft: ${error.message}`);
    return data.length ? text({ discarded: draftId }) : failure(`Draft ${draftId} ${GONE}`);
  });

  return mcp;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (new URL(request.url).pathname.endsWith("/.well-known/oauth-protected-resource")) {
    return Response.json({
      resource: RESOURCE,
      authorization_servers: [`${PUBLIC_URL}/auth/v1`],
      bearer_methods_supported: ["header"],
      resource_name: "Brewprint",
    }, { headers: CORS });
  }
  if (!request.headers.has("Authorization")) return unauthorized();
  const client = await userClient(request);
  if (!client) return unauthorized("invalid_token");

  // Stateless: each request gets its own server and transport, so nothing is shared between users.
  const transport = new WebStandardStreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true,
  });
  await server(client).connect(transport);
  const response = await transport.handleRequest(request);
  Object.entries(CORS).forEach(([name, value]) => response.headers.set(name, value));
  return response;
});
