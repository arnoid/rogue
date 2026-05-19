#version 450

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec3 v_worldPos;
layout(location = 2) in vec3 v_normal;

// Light data: up to MAX_LIGHTS lights packed into a UBO.
// Keep MAX_LIGHTS in sync with `SimpleUI.MAX_LIGHTS` on the host side.
#define MAX_LIGHTS 128

// ── Forward+ tile binning ────────────────────────────────────────────
// Must match `SimpleUI.LIGHT_TILE_SIZE` and `MAX_LIGHTS_PER_TILE`.
#define LIGHT_TILE_SIZE 16
#define MAX_LIGHTS_PER_TILE 32

layout(set = 0, binding = 0) uniform LightingUBO {
    int lightCount;
    int gridW;
    int gridH;
    int gridD;
    // Second 16-byte header slot: x = ambient intensity, yzw reserved.
    vec4 ambientParams;
    // Third 16-byte header slot: xyz = world-voxel origin of the occupancy
    // grid window. The grid is anchored to this origin in absolute world
    // coordinates; the shader subtracts it before indexing. Letting the
    // window track the player lets the host upload only the geometry near
    // the visible lights instead of the whole world.
    vec4 gridOrigin;
    // Fourth 16-byte header slot — Forward+ screen/tile parameters:
    //   x = screen width in pixels
    //   y = screen height in pixels
    //   z = tile size in pixels (== LIGHT_TILE_SIZE, kept for sanity check)
    //   w = number of tile columns (tilesX) — used to index into
    //       `tileLightCount` / `tileLightIndices` without per-frame divides
    vec4 screenParams;
    vec4 lightPosIntensity[MAX_LIGHTS];   // xyz = position, w = intensity
    vec4 lightColorRadius[MAX_LIGHTS];    // rgb = color, a = radius
} lighting;

// 3D occupancy grid as SSBO: 1 uint per cell
// Bit flags: bit0=north(Y+), bit1=south(Y-), bit2=east(X+), bit3=west(X-)
//            bit4=floor(Z-), bit5=ceiling(Z+)
// bits 7-15: shadow triangle count for this cell (0-511)
// bits 16-31: shadow triangle start index in shadow SSBO
layout(set = 0, binding = 1) readonly buffer OccupancyGrid {
    uint cells[];
} grid;

// Shadow mesh triangles SSBO: packed as vec4 triplets per triangle
// Each triangle = 3 consecutive vec4s: (v0.xyz, 0), (v1.xyz, 0), (v2.xyz, 0)
layout(set = 0, binding = 2) readonly buffer ShadowTriangles {
    vec4 tris[];
} shadowTris;

// Forward+ per-tile light count (binding 3) and indices (binding 4).
// `tileLightCount[t]` = number of lights overlapping tile t.
// `tileLightIndices[t * MAX_LIGHTS_PER_TILE + i]` = i'th light index for
// tile t = ty * tilesX + tx, where tx = int(gl_FragCoord.x / LIGHT_TILE_SIZE)
// and ty = int(gl_FragCoord.y / LIGHT_TILE_SIZE). Iterating only this
// per-tile list bounds the per-fragment light loop regardless of how
// many lights the host uploaded this frame.
layout(set = 0, binding = 3) readonly buffer TileLightCount {
    uint counts[];
} tileLightCount;
layout(set = 0, binding = 4) readonly buffer TileLightIndices {
    uint indices[];
} tileLightIndices;

layout(location = 0) out vec4 outColor;

/// Get wall flags for grid cell (ix,iy,iz) — returns lower 7 bits only
uint getWallFlags(int ix, int iy, int iz) {
    // ix/iy/iz are absolute world voxel coordinates. Translate into the
    // window-local index space first; cells outside the window contribute
    // nothing (treated as empty space — they're outside the lit region
    // anyway, so the DDA either won't reach them or won't care).
    int lx = ix - int(lighting.gridOrigin.x);
    int ly = iy - int(lighting.gridOrigin.y);
    int lz = iz - int(lighting.gridOrigin.z);
    if (lx < 0 || lx >= lighting.gridW ||
        ly < 0 || ly >= lighting.gridH ||
        lz < 0 || lz >= lighting.gridD) return 0u;
    uint idx = uint(lz * lighting.gridW * lighting.gridH + ly * lighting.gridW + lx);
    return grid.cells[idx] & 0x7Fu;
}

/// Get shadow triangle range for a grid cell.
/// Returns (startIndex, count) packed from bits 16-31 and 7-15.
void getShadowTriRange(int ix, int iy, int iz, out int start, out int count) {
    int lx = ix - int(lighting.gridOrigin.x);
    int ly = iy - int(lighting.gridOrigin.y);
    int lz = iz - int(lighting.gridOrigin.z);
    if (lx < 0 || lx >= lighting.gridW ||
        ly < 0 || ly >= lighting.gridH ||
        lz < 0 || lz >= lighting.gridD) {
        start = 0; count = 0; return;
    }
    uint idx = uint(lz * lighting.gridW * lighting.gridH + ly * lighting.gridW + lx);
    uint cell = grid.cells[idx];
    start = int((cell >> 16u) & 0xFFFFu);
    count = int((cell >> 7u) & 0x1FFu);
}

/// Möller–Trumbore ray-triangle intersection.
/// Returns true if ray (orig, dir) hits triangle (v0,v1,v2) at t in (0, maxT).
bool rayTriangleIntersect(vec3 orig, vec3 dir, vec3 v0, vec3 v1, vec3 v2, float maxT) {
    vec3 e1 = v1 - v0;
    vec3 e2 = v2 - v0;
    vec3 h = cross(dir, e2);
    float a = dot(e1, h);
    if (abs(a) < 1e-6) return false;
    float f = 1.0 / a;
    vec3 s = orig - v0;
    float u = f * dot(s, h);
    if (u < 0.0 || u > 1.0) return false;
    vec3 q = cross(s, e1);
    float v = f * dot(dir, q);
    if (v < 0.0 || u + v > 1.0) return false;
    float t = f * dot(e2, q);
    return t > 0.001 && t < maxT;
}

/// Test ray against all shadow triangles in a given cell.
bool hitsShadowMesh(vec3 orig, vec3 dir, float maxT, int cellX, int cellY, int cellZ) {
    int start, count;
    getShadowTriRange(cellX, cellY, cellZ, start, count);
    for (int i = 0; i < count && i < 256; i++) {
        int base = (start + i) * 3; // 3 vec4s per triangle
        vec3 v0 = shadowTris.tris[base + 0].xyz;
        vec3 v1 = shadowTris.tris[base + 1].xyz;
        vec3 v2 = shadowTris.tris[base + 2].xyz;
        if (rayTriangleIntersect(orig, dir, v0, v1, v2, maxT)) return true;
    }
    return false;
}

/// Ray-march through the voxel grid from 'from' to 'to' using 3D DDA.
/// Tests wall flags at cell boundaries and shadow mesh triangles within cells.
bool isOccluded(vec3 from, vec3 to) {
    vec3 d = to - from;
    float dist = length(d);
    if (dist < 0.02) return false;

    vec3 dir = d / dist;

    // Current voxel
    int ix = int(floor(from.x));
    int iy = int(floor(from.y));
    int iz = int(floor(from.z));

    // Target voxel
    int ex = int(floor(to.x));
    int ey = int(floor(to.y));
    int ez = int(floor(to.z));

    // Step direction
    int sx = dir.x >= 0.0 ? 1 : -1;
    int sy = dir.y >= 0.0 ? 1 : -1;
    int sz = dir.z >= 0.0 ? 1 : -1;

    // Distance along ray to next voxel boundary on each axis
    float tMaxX = abs(dir.x) > 1e-6
        ? ((dir.x >= 0.0 ? float(ix + 1) : float(ix)) - from.x) / dir.x
        : 1e30;
    float tMaxY = abs(dir.y) > 1e-6
        ? ((dir.y >= 0.0 ? float(iy + 1) : float(iy)) - from.y) / dir.y
        : 1e30;
    float tMaxZ = abs(dir.z) > 1e-6
        ? ((dir.z >= 0.0 ? float(iz + 1) : float(iz)) - from.z) / dir.z
        : 1e30;

    // How far along ray to move to cross one full voxel on each axis
    float tDeltaX = abs(dir.x) > 1e-6 ? float(sx) / dir.x : 1e30;
    float tDeltaY = abs(dir.y) > 1e-6 ? float(sy) / dir.y : 1e30;
    float tDeltaZ = abs(dir.z) > 1e-6 ? float(sz) / dir.z : 1e30;

    // March through voxels (max 64 steps to avoid infinite loops)
    for (int step = 0; step < 64; step++) {
        // Reached the target voxel — no occlusion
        if (ix == ex && iy == ey && iz == ez) return false;

        // Shadow mesh intersection: test ray against mesh triangles in this cell.
        if (hitsShadowMesh(from, dir, dist, ix, iy, iz)) return true;

        // Determine which axis boundary to cross next and check for wall edges
        if (tMaxX < tMaxY) {
            if (tMaxX < tMaxZ) {
                // Crossing X boundary (east/west wall edge)
                uint curFlags = getWallFlags(ix, iy, iz);
                int nx = ix + sx;
                uint nextFlags = getWallFlags(nx, iy, iz);
                if (sx > 0) {
                    // Moving +X: check east wall of current cell or west wall of next cell
                    if ((curFlags & 4u) != 0u || (nextFlags & 8u) != 0u) return true;
                } else {
                    // Moving -X: check west wall of current cell or east wall of next cell
                    if ((curFlags & 8u) != 0u || (nextFlags & 4u) != 0u) return true;
                }
                ix = nx; tMaxX += tDeltaX;
            } else {
                // Crossing Z boundary (floor/ceiling)
                uint curFlags = getWallFlags(ix, iy, iz);
                int nz = iz + sz;
                uint nextFlags = getWallFlags(ix, iy, nz);
                if (sz > 0) {
                    // Moving +Z: check ceiling of current cell or floor of next cell
                    if ((curFlags & 32u) != 0u || (nextFlags & 16u) != 0u) return true;
                } else {
                    // Moving -Z: check floor of current cell or ceiling of next cell
                    if ((curFlags & 16u) != 0u || (nextFlags & 32u) != 0u) return true;
                }
                iz = nz; tMaxZ += tDeltaZ;
            }
        } else {
            if (tMaxY < tMaxZ) {
                // Crossing Y boundary (north/south wall edge)
                uint curFlags = getWallFlags(ix, iy, iz);
                int ny = iy + sy;
                uint nextFlags = getWallFlags(ix, ny, iz);
                if (sy > 0) {
                    // Moving +Y: check north wall of current cell or south wall of next cell
                    if ((curFlags & 1u) != 0u || (nextFlags & 2u) != 0u) return true;
                } else {
                    // Moving -Y: check south wall of current cell or north wall of next cell
                    if ((curFlags & 2u) != 0u || (nextFlags & 1u) != 0u) return true;
                }
                iy = ny; tMaxY += tDeltaY;
            } else {
                // Crossing Z boundary (floor/ceiling)
                uint curFlags = getWallFlags(ix, iy, iz);
                int nz = iz + sz;
                uint nextFlags = getWallFlags(ix, iy, nz);
                if (sz > 0) {
                    // Moving +Z: check ceiling of current cell or floor of next cell
                    if ((curFlags & 32u) != 0u || (nextFlags & 16u) != 0u) return true;
                } else {
                    // Moving -Z: check floor of current cell or ceiling of next cell
                    if ((curFlags & 16u) != 0u || (nextFlags & 32u) != 0u) return true;
                }
                iz = nz; tMaxZ += tDeltaZ;
            }
        }
    }
    return false;
}

void main() {
    vec3 N = normalize(v_normal);
    vec3 baseColor = v_color.rgb;

    int numLights = lighting.lightCount;
    if (numLights <= 0) {
        // No lights — apply ambient-only fill so an Arena scene (ambient
        // == 0) reads as true black instead of falling back to the raw
        // vertex base color, which would make "no nearby room lights"
        // look identical to "fully lit room".
        float a = lighting.ambientParams.x;
        outColor = vec4(baseColor * a, v_color.a);
        return;
    }

    // Offset world position along normal to avoid self-shadowing.
    // A generous offset pushes the ray origin well past the fragment's own
    // surface so that ray-triangle intersection min_t can reject nearby
    // self-hits while still catching occluder geometry.
    vec3 surfacePos = v_worldPos + N * 0.15;

    // Ambient minimum (host-controlled; 0 disables ambient fill entirely).
    float ambient = lighting.ambientParams.x;
    vec3 totalLight = vec3(ambient);

    // ── Top-K light selection ────────────────────────────────────────────
    //
    // Even with the host-side room/frustum cull, a busy scene can still
    // ship 30+ lights to the GPU; without a per-pixel cap each one would
    // trigger a full DDA + shadow-mesh ray-march, and cost grows linearly
    // with light count. Empirically, almost every fragment is dominated
    // by at most a handful of nearby lights — fixtures further away
    // contribute fractions of a percent due to the `1/(1+0.05·d²)`
    // attenuation. So we:
    //
    //   1. Score every light with a cheap *upper bound* of its possible
    //      contribution: NdotL × window(d/r) / (1 + 0.05·d²). No shadow
    //      ray needed yet.
    //   2. Keep only the top MAX_PER_PIXEL_LIGHTS scores.
    //   3. Pay the expensive `isOccluded` ray-march only for those.
    //
    // With MAX_PER_PIXEL_LIGHTS = 6, fragment cost is bounded regardless
    // of `numLights` — adding more distant lights to the scene no longer
    // tanks frame time.
    const int MAX_PER_PIXEL_LIGHTS = 6;
    int   topIdx  [MAX_PER_PIXEL_LIGHTS];
    float topScore[MAX_PER_PIXEL_LIGHTS];
    for (int s = 0; s < MAX_PER_PIXEL_LIGHTS; s++) {
        topIdx[s] = -1;
        topScore[s] = 0.0;
    }

    // ── Forward+ tile lookup ────────────────────────────────────────────
    // Pull the per-tile light count and starting index out of the SSBOs
    // keyed by gl_FragCoord. If screenParams hasn't been set yet (host
    // hasn't called updateLightTiles) we fall back to iterating the full
    // lightCount, which keeps the shader correct during the first frame
    // and any path that bypasses Forward+.
    int tilesX = int(lighting.screenParams.w);
    int tileSize = int(lighting.screenParams.z);
    int tileLightN;
    int tileLightBase;
    if (tilesX > 0 && tileSize > 0) {
        int tx = int(gl_FragCoord.x) / tileSize;
        int ty = int(gl_FragCoord.y) / tileSize;
        int tIdx = ty * tilesX + tx;
        tileLightN = int(tileLightCount.counts[tIdx]);
        tileLightBase = tIdx * MAX_LIGHTS_PER_TILE;
    } else {
        tileLightN = numLights;
        tileLightBase = -1; // sentinel for "ignore tileLightIndices; iterate sequentially"
    }

    for (int k = 0; k < tileLightN && k < MAX_LIGHTS; k++) {
        int i = (tileLightBase >= 0)
            ? int(tileLightIndices.indices[tileLightBase + k])
            : k;
        if (i < 0 || i >= numLights) continue;

        vec3 lightPos = lighting.lightPosIntensity[i].xyz;
        float intensity = lighting.lightPosIntensity[i].w;
        float radius = lighting.lightColorRadius[i].a;

        vec3 toLight = lightPos - surfacePos;
        float distSq = dot(toLight, toLight);
        if (distSq < 0.001) continue;
        float dist = sqrt(distSq);
        if (dist > radius) continue;

        vec3 L = toLight / dist;
        float NdotL = max(dot(N, L), 0.0);
        if (NdotL <= 0.0) continue;

        // Same attenuation curve as the lit pass below — cheap to evaluate
        // and matches the actual contribution rank.
        float kAtt = clamp(1.0 - pow(dist / radius, 4.0), 0.0, 1.0);
        float window = kAtt * kAtt;
        float score = NdotL * intensity * window / (1.0 + distSq * 0.05);
        if (score <= 0.0) continue;

        // Insertion sort into the top-K array (K is tiny, so this is
        // cheaper than any branchless alternative).
        int insertAt = -1;
        float minScore = topScore[0];
        int minSlot = 0;
        for (int s = 0; s < MAX_PER_PIXEL_LIGHTS; s++) {
            if (topIdx[s] < 0) { insertAt = s; break; }
            if (topScore[s] < minScore) {
                minScore = topScore[s];
                minSlot = s;
            }
        }
        if (insertAt < 0 && score > minScore) insertAt = minSlot;
        if (insertAt >= 0) {
            topIdx[insertAt] = i;
            topScore[insertAt] = score;
        }
    }

    // ── Shade with the surviving lights only ────────────────────────────
    for (int s = 0; s < MAX_PER_PIXEL_LIGHTS; s++) {
        int i = topIdx[s];
        if (i < 0) continue;

        vec3 lightPos = lighting.lightPosIntensity[i].xyz;
        float intensity = lighting.lightPosIntensity[i].w;
        vec3 lightColor = lighting.lightColorRadius[i].rgb;
        float radius = lighting.lightColorRadius[i].a;

        vec3 toLight = lightPos - surfacePos;
        float dist = length(toLight);
        vec3 L = toLight / dist;
        float NdotL = max(dot(N, L), 0.0);

        // Same-cell shortcut: skip the DDA when the light sits inside the
        // fragment's own voxel — it can never be self-occluded by mesh
        // geometry of its own cell at < 1 voxel distance.
        bool sameCell =
            int(floor(surfacePos.x)) == int(floor(lightPos.x)) &&
            int(floor(surfacePos.y)) == int(floor(lightPos.y)) &&
            int(floor(surfacePos.z)) == int(floor(lightPos.z));
        if (!sameCell && isOccluded(surfacePos, lightPos)) continue;

        // Distance attenuation.
        //
        // The previous curve `intensity / (1 + distSq * 0.1)` dropped to
        // ~10% brightness at d=10 regardless of the light's radius, so a
        // radius=20 light barely lit the far half of a 12×12 room before
        // the hard `dist > radius` cutoff. We now combine a gentle
        // inverse-square term with a "windowed" falloff that drives the
        // contribution smoothly to zero exactly at `radius`.
        float distSq = dist * dist;
        float kAtt = clamp(1.0 - pow(dist / radius, 4.0), 0.0, 1.0);
        float window = kAtt * kAtt;
        float attenuation = intensity * window / (1.0 + distSq * 0.05);
        totalLight += lightColor * attenuation * NdotL;
    }

    totalLight = clamp(totalLight, vec3(0.0), vec3(1.0));
    outColor = vec4(baseColor * totalLight, v_color.a);
}
