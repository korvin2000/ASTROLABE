import { Router } from "./router.ts";
import type { Request, Response } from "./router.ts";

export { Router, normalizeCurrency } from "./router.ts";
export type { Request, Response, Handler } from "./router.ts";

/** Answers a user lookup; takes the request only, like its Python twin. */
export function handleUser(req: Request): Response {
  if (req.userId === undefined) {
    return { status: 400, body: "userId is required" };
  }
  return { status: 200, body: `user ${req.userId}` };
}

const routes = { "/user": handleUser };

export function dispatch(req: Request): Response {
  return new Router(routes).route(req);
}
