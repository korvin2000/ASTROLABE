import { test } from "node:test";
import assert from "node:assert/strict";
import { dispatch, handleUser } from "../src/index.ts";

// `smoke` also exists in test/router.test.ts (D-27, IX-13).

test("smoke", () => {
  assert.equal(dispatch({ path: "/user", userId: 7 }).status, 200);
});

test("missing userId is 400", () => {
  assert.equal(handleUser({ path: "/user" }).status, 400);
});
