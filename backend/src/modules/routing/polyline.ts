// SPDX-License-Identifier: AGPL-3.0-only

/** [minLongitude, minLatitude, maxLongitude, maxLatitude] */
export type BoundingBox = [number, number, number, number];

const PRECISION = 1e6;

/**
 * Bounding box of an encoded polyline with six decimals, or undefined when the text is not one.
 *
 * The format (Google's "encoded polyline algorithm", with 1e6 instead of 1e5): each number is the
 * difference to the previous latitude or longitude, shifted left by one bit with the sign in bit
 * 0, cut into 5-bit groups from the low end, each group plus 63 as a character, and every group
 * but the last of a number has bit 5 set. The points themselves are not kept: only the box is
 * needed here, and a route is never stored.
 */
export function polyline6Bounds(encoded: string): BoundingBox | undefined {
  let index = 0;
  let latitude = 0;
  let longitude = 0;
  const box: BoundingBox = [Infinity, Infinity, -Infinity, -Infinity];

  const next = (): number | undefined => {
    let result = 0;
    let shift = 0;
    for (;;) {
      const group = encoded.charCodeAt(index++) - 63;
      // NaN past the end, or a character outside the alphabet; no coordinate needs over 35 bits.
      if (!(group >= 0 && group <= 63) || shift > 30) return undefined;
      result += (group & 0x1f) * 2 ** shift;
      shift += 5;
      if (group < 0x20) break;
    }
    return result % 2 === 1 ? -(result + 1) / 2 : result / 2;
  };

  while (index < encoded.length) {
    const dLat = next();
    const dLon = dLat === undefined ? undefined : next();
    if (dLat === undefined || dLon === undefined) return undefined;
    latitude += dLat;
    longitude += dLon;
    const lat = latitude / PRECISION;
    const lon = longitude / PRECISION;
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return undefined;
    box[0] = Math.min(box[0], lon);
    box[1] = Math.min(box[1], lat);
    box[2] = Math.max(box[2], lon);
    box[3] = Math.max(box[3], lat);
  }
  return box[0] === Infinity ? undefined : box;
}
