// SPDX-License-Identifier: AGPL-3.0-only
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

/**
 * Fixtures for the routing tests. Places are PUBLIC landmarks (two railway stations and a museum
 * building, as mapped in OpenStreetMap); nothing here is a person's location. The geometry is a
 * hand-made three-point line, not a real route.
 */
export const STATION_A = { latitude: 22.58287, longitude: 88.34281 };
export const MONUMENT = { latitude: 22.54508, longitude: 88.34264 };
export const STATION_NORTH = { latitude: 26.68286, longitude: 88.44248 };
/** Round invented values, far outside the covered area. */
export const OUTSIDE = { latitude: 10.5, longitude: 20.5 };

/** Encodes [latitude, longitude] pairs as a polyline with six decimals. */
export function encodePolyline6(points: [number, number][]): string {
  let out = '';
  let prevLat = 0;
  let prevLon = 0;
  const encode = (value: number) => {
    let v = value < 0 ? -value * 2 - 1 : value * 2;
    while (v >= 0x20) {
      out += String.fromCharCode((0x20 | (v % 32)) + 63);
      v = Math.floor(v / 32);
    }
    out += String.fromCharCode(v + 63);
  };
  for (const [lat, lon] of points) {
    const latE6 = Math.round(lat * 1e6);
    const lonE6 = Math.round(lon * 1e6);
    encode(latE6 - prevLat);
    encode(lonE6 - prevLon);
    prevLat = latE6;
    prevLon = lonE6;
  }
  return out;
}

export const GEOMETRY = encodePolyline6([
  [22.58287, 88.34281],
  [22.565, 88.351],
  [22.54508, 88.34264],
]);

export const osrmOk = (count = 1) => ({
  code: 'Ok',
  routes: Array.from({ length: count }, (_, i) => ({
    distance: 5100.4 + i * 400,
    duration: 3660.6 + i * 300,
    geometry: GEOMETRY,
    legs: [],
  })),
  waypoints: [],
});

export interface RecordedRequest {
  url: string;
  headers: IncomingHttpHeaders;
}

export interface FakeServer {
  /** http://127.0.0.1:<port>, no trailing slash. */
  baseUrl: string;
  requests: RecordedRequest[];
  close: () => Promise<void>;
}

/** A real HTTP server on a free local port that answers every request through `reply`. */
export async function startFakeServer(
  reply: (request: RecordedRequest) => { status: number; body: string; delayMs?: number },
): Promise<FakeServer> {
  const requests: RecordedRequest[] = [];
  const server: Server = createServer((req, res) => {
    const recorded = { url: req.url ?? '', headers: req.headers };
    requests.push(recorded);
    const { status, body, delayMs } = reply(recorded);
    setTimeout(() => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(body);
    }, delayMs ?? 0);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  return {
    baseUrl: `http://127.0.0.1:${String(port)}`,
    requests,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => {
          resolve();
        });
      }),
  };
}

/** An unsigned token with the given expiry: the adapter reads `exp` and never verifies. */
export function fakeIdToken(expiresAtMs: number, marker = 'FAKE'): string {
  const part = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${part({ alg: 'none' })}.${part({ exp: Math.floor(expiresAtMs / 1000), marker })}.FAKE_SIGNATURE`;
}
