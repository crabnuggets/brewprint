// Brewprint's MCP server: lets Claude read the signed-in user's beans, grinders, recipes and brews.
// Claude signs in through Supabase Auth's OAuth server; row-level security keeps it to that user's
// rows and to reading them (supabase/migrations/20261121090000_read_only_oauth_clients.sql).
import "@supabase/functions-js/edge-runtime.d.ts";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { z } from "zod";
import { brewDetail, type BrewFilter, brewSummary, findBrews, library, type Row } from "./brews.ts";

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

const INSTRUCTIONS = `Brewprint is the user's filter coffee journal. These tools read it; nothing here can change it.
A brew record keeps the beans, grinder, grind setting and recipe as they were on that day, plus the timing,
scale readings and the user's tasting journal. Grind settings are ticks from each grinder's own reference
point, so compare them only within one grinder. Measured water is the scale's reading after the last pour,
not the weight of the drink. The app records no water temperature, roast date or overall score; ask the user
when they matter.`;

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

const text = (value: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(value, null, 1) }] });

function server(client: SupabaseClient) {
  const mcp = new McpServer({ name: "brewprint", version: "0.1.0" }, { instructions: INSTRUCTIONS });
  const read = { readOnlyHint: true, openWorldHint: false };

  mcp.registerTool("get_library", {
    title: "Beans, grinders and recipes",
    description: "The user's beans (with roaster, origin, process and expected tasting notes), grinders and recipes, " +
      "plus how many brews each beans and recipe has. Archived items are left out unless asked for.",
    inputSchema: { includeArchived: z.boolean().optional().describe("Include archived beans, grinders and recipes.") },
    annotations: read,
  }, async ({ includeArchived }: { includeArchived?: boolean }) => {
    const { beans, grinders, recipes, brews } = library(await readRows(client));
    const shown = <T extends { archived: boolean }>(items: T[]) =>
      items.filter((item) => includeArchived || !item.archived);
    return text({
      beans: shown(beans).map((item) => ({ ...item, brews: brews.filter((brew) => brew.beans.id === item.id).length })),
      grinders: shown(grinders),
      recipes: shown(recipes).map((item) => ({
        ...item,
        brews: brews.filter((brew) => brew.recipe.id === item.id).length,
      })),
      totalBrews: brews.length,
    });
  });

  mcp.registerTool("list_brews", {
    title: "List brews",
    description: "Brew records, newest first, each with its beans, grind, recipe, timing and tasting notes in brief. " +
      "Use get_brew for a brew's pulses and scale readings.",
    inputSchema: {
      beans: z.string().optional().describe("Part of the beans' name or roaster."),
      recipe: z.string().optional().describe("Part of the recipe's name."),
      since: z.string().optional().describe("Earliest day, YYYY-MM-DD."),
      until: z.string().optional().describe("Latest day, YYYY-MM-DD."),
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
