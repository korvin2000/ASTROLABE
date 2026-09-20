export type Request = { path: string; userId?: number };

export type Response = { status: number; body: string };

export type Handler = (req: Request) => Response;

export const CURRENCIES = ["EUR", "USD", "GBP"] as const;

export function normalizeCurrency(raw: string): string {
  const code = raw.trim().toUpperCase();
  if (!(CURRENCIES as readonly string[]).includes(code)) {
    throw new Error(`unknown currency: ${raw}`);
  }
  return code.toLowerCase();
}

export class Router {
  private readonly routes: Map<string, Handler>;

  constructor(routes: Record<string, Handler>) {
    this.routes = new Map(Object.entries(routes));
  }

  route(req: Request): Response {
    const handler = this.routes.get(req.path);
    if (handler === undefined) {
      return { status: 404, body: `no route for ${req.path}` };
    }
    return handler(req);
  }

  paths(): string[] {
    return [...this.routes.keys()].sort();
  }
}
