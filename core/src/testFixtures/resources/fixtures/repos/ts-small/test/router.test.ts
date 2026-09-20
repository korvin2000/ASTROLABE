import { test } from "node:test";
import assert from "node:assert/strict";
import { Router, normalizeCurrency } from "../src/router.ts";
import type { Request, Response } from "../src/router.ts";

// `smoke` also exists in test/index.test.ts: two files, one test name, two
// distinct namespaced identities (D-27, IX-13). The junit reporter attributes
// each one to its file; the spec and tap reporters do not.

const ok: (req: Request) => Response = (req) => ({ status: 200, body: req.path });

test("smoke", () => {
  assert.equal(new Router({ "/user": ok }).route({ path: "/user" }).status, 200);
});

test("unknown path is 404", () => {
  assert.equal(new Router({ "/user": ok }).route({ path: "/nope" }).status, 404);
});

const currencyCases: ReadonlyArray<readonly [string, string]> = [
  ["EUR", "eur"],
  ["Usd", "usd"],
  [" gbp ", "gbp"],
];

for (const [raw, expected] of currencyCases) {
  test(`normalizeCurrency ${expected}`, () => {
    assert.equal(normalizeCurrency(raw), expected);
  });
}
