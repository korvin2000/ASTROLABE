import { createServer } from 'node:http';
import { postQuote } from './routes.js';

const LIMIT_BYTES = 64 * 1024;

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > LIMIT_BYTES) reject(new RangeError('body too large'));
      else chunks.push(chunk);
    });
    request.on('end', () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : null);
      } catch {
        reject(new SyntaxError('invalid JSON'));
      }
    });
    request.on('error', reject);
  });
}

function send(response, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': bytes.length });
  response.end(bytes);
}

/** The quote API: `POST /quote`; anything else is 404. */
export function createQuoteServer() {
  return createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/quote') return send(response, 404, { error: 'not found' });
    let body;
    try {
      body = await readJson(request);
    } catch (error) {
      return send(response, 400, { error: error.message });
    }
    const answer = postQuote(body);
    send(response, answer.status, answer.body);
  });
}
