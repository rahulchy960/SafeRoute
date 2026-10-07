// SPDX-License-Identifier: AGPL-3.0-only

export type RoutingMode = 'walking' | 'driving';

export interface Coordinate {
  latitude: number;
  longitude: number;
}

export interface RouteQuery {
  origin: Coordinate;
  destination: Coordinate;
  mode: RoutingMode;
}

/** One route, in a shape that names no routing engine. */
export interface ProviderRoute {
  distanceMeters: number;
  durationSeconds: number;
  /** The whole line as an encoded polyline with six decimals ("polyline6"). */
  geometry: string;
}

/**
 * The boundary to a routing engine (Plan v7 §4, ADR 0020). Everything outside
 * src/modules/routing/providers talks to this interface.
 */
export interface RoutingProvider {
  /** Credit line that must be shown with a route. */
  readonly attribution: string;
  /** Best route first, at most three. One attempt: no retries inside the API. */
  route(query: RouteQuery): Promise<ProviderRoute[]>;
}

export type RoutingFailure =
  /** The engine found the points but no way between them. */
  | 'no_route'
  /** A point could not be put on the road network. */
  | 'not_routable'
  /** Includes a cold start that took longer than ROUTING_TIMEOUT_MS. */
  | 'timeout'
  | 'network'
  /** No ID token, or the service refused it. Our problem, never the user's. */
  | 'auth'
  | 'upstream'
  | 'malformed'
  | 'oversized';

/**
 * The only error an adapter throws. It carries a kind and nothing else: no URL (the request URL
 * holds the coordinates and the service address), no token, no response body. `message` is the
 * kind, so it is safe to log.
 */
export class RoutingError extends Error {
  readonly kind: RoutingFailure;

  constructor(kind: RoutingFailure) {
    super(`routing ${kind}`);
    this.name = 'RoutingError';
    this.kind = kind;
  }
}
