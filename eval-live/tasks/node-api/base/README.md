# tally

Price quotes, in three packages without dependencies (Node built-ins only, no `npm install`):

- `packages/pricing` — `quote` and `formatMoney`, the tax rates per region;
- `packages/api` — the quote API over `node:http` (`POST /quote`);
- `packages/cli` — `node packages/cli/src/main.js --region EU A-1:2:450 B-7:1:1299`.

The packages import each other by relative path. Tests: `node --test` (or `npm test`) from the root.
