import { assert, assertEquals, assertRejects } from "@std/assert";
import {
  assertion,
  type Devices,
  Messenger,
  messengerFromEnv,
  notifyDraftsChanged,
  type ServiceAccount,
} from "./push.ts";

const keys = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);
const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keys.privateKey));
const pem = `-----BEGIN PRIVATE KEY-----\n${
  btoa(String.fromCharCode(...pkcs8)).match(/.{1,64}/g)!.join("\n")
}\n-----END PRIVATE KEY-----\n`;
const account: ServiceAccount = {
  project_id: "brewprint-test",
  client_email: "push@brewprint-test.iam.gserviceaccount.com",
  private_key: pem,
};

const decode = (part: string) =>
  JSON.parse(new TextDecoder().decode(Uint8Array.from(
    atob(part.replace(/-/g, "+").replace(/_/g, "/")),
    (char) => char.charCodeAt(0),
  )));

Deno.test("the assertion is a JWT signed by the service account's key, for Firebase messaging", async () => {
  const jwt = await assertion(account, 1795400000000);
  const [header, claims, signature] = jwt.split(".");
  assertEquals(decode(header), { alg: "RS256", typ: "JWT" });
  assertEquals(decode(claims), {
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: 1795400000,
    exp: 1795403600,
  });
  const bytes = Uint8Array.from(atob(signature.replace(/-/g, "+").replace(/_/g, "/")), (char) => char.charCodeAt(0));
  assert(
    await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      keys.publicKey,
      bytes,
      new TextEncoder().encode(`${header}.${claims}`),
    ),
  );
});

/** A fetch that answers Google's token endpoint and Firebase, recording each request. */
function google(send: (token: string) => Response) {
  const requests: { url: string; body: string; authorization: string | null }[] = [];
  const fetcher = ((input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const body = String(init?.body ?? "");
    requests.push({ url, body, authorization: new Headers(init?.headers).get("Authorization") });
    if (url === "https://oauth2.googleapis.com/token") {
      return Promise.resolve(Response.json({ access_token: `access-${requests.length}`, expires_in: 3600 }));
    }
    return Promise.resolve(send(JSON.parse(body).message.token));
  }) as typeof fetch;
  return { requests, fetcher };
}

Deno.test("a data-only message reaches each token, reusing one access token", async () => {
  const { requests, fetcher } = google(() => Response.json({ name: "projects/brewprint-test/messages/1" }));
  const messenger = new Messenger(account, fetcher, () => 1795400000000);
  assertEquals(await messenger.draftsChanged("phone-a"), true);
  assertEquals(await messenger.draftsChanged("phone-b"), true);
  const sends = requests.filter((it) => it.url.includes("fcm.googleapis.com"));
  assertEquals(requests.filter((it) => it.url.includes("oauth2")).length, 1);
  assertEquals(sends.map((it) => it.url), [
    "https://fcm.googleapis.com/v1/projects/brewprint-test/messages:send",
    "https://fcm.googleapis.com/v1/projects/brewprint-test/messages:send",
  ]);
  assertEquals(sends[0].authorization, "Bearer access-1");
  assertEquals(JSON.parse(sends[0].body), {
    message: { token: "phone-a", data: { type: "drafts" }, android: { priority: "HIGH" } },
  });
});

Deno.test("an expired access token is renewed", async () => {
  let now = 1795400000000;
  const { requests, fetcher } = google(() => Response.json({}));
  const messenger = new Messenger(account, fetcher, () => now);
  await messenger.draftsChanged("phone-a");
  now += 3600 * 1000;
  await messenger.draftsChanged("phone-a");
  assertEquals(requests.filter((it) => it.url.includes("oauth2")).length, 2);
});

Deno.test("a token Firebase no longer knows is reported; other failures throw", async () => {
  const unregistered = Response.json(
    { error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } },
    { status: 404 },
  );
  const { fetcher } = google((token) => token === "gone" ? unregistered : new Response("busy", { status: 503 }));
  const messenger = new Messenger(account, fetcher, () => 1795400000000);
  assertEquals(await messenger.draftsChanged("gone"), false);
  await assertRejects(() => messenger.draftsChanged("phone-a"), Error, "503");
});

function phones(tokens: string[] | Error) {
  const forgotten: string[][] = [];
  const devices: Devices = {
    tokens: () => tokens instanceof Error ? Promise.reject(tokens) : Promise.resolve(tokens),
    forget: (_, gone) => {
      forgotten.push(gone);
      return Promise.resolve();
    },
  };
  return { forgotten, devices };
}

Deno.test("every phone hears, and phones Firebase forgot are forgotten here", async () => {
  const { requests, fetcher } = google((token) =>
    token === "old" ? new Response("UNREGISTERED", { status: 404 }) : Response.json({})
  );
  const { forgotten, devices } = phones(["phone-a", "old", "phone-b"]);
  await notifyDraftsChanged("user-1", devices, new Messenger(account, fetcher, () => 1795400000000));
  assertEquals(requests.filter((it) => it.url.includes("fcm")).length, 3);
  assertEquals(forgotten, [["old"]]);
});

Deno.test("telling phones never fails the draft", async () => {
  const { fetcher } = google(() => new Response("down", { status: 500 }));
  const { forgotten, devices } = phones(["phone-a"]);
  await notifyDraftsChanged("user-1", devices, new Messenger(account, fetcher));
  await notifyDraftsChanged("user-1", phones(new Error("no table")).devices, new Messenger(account, fetcher));
  assertEquals(forgotten, []);
});

Deno.test("without the secret, nothing is sent", async () => {
  assertEquals(messengerFromEnv(undefined), null);
  assertEquals(messengerFromEnv("not json"), null);
  const { forgotten, devices } = phones(["phone-a"]);
  await notifyDraftsChanged("user-1", devices, null);
  assertEquals(forgotten, []);
});
