#version 450

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec3 v_worldPos;
layout(location = 2) in vec3 v_normal;

// Light data: up to 32 lights packed into a UBO
// Each light: vec4(pos.xyz, intensity), vec4(color.rgb, radius)
layout(set = 0, binding = 0) uniform LightingUBO {
    int lightCount;
    int gridW;
    int gridH;
    int gridD;
    vec4 lightPosIntensity[32];   // xyz = position, w = intensity
    vec4 lightColorRadius[32];    // rgb = color, a = radius
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

layout(location = 0) out vec4 outColor;

/// Get wall flags for grid cell (ix,iy,iz) — returns lower 7 bits only
uint getWallFlags(int ix, int iy, int iz) {
    if (ix < 0 || ix >= lighting.gridW ||
        iy < 0 || iy >= lighting.gridH ||
        iz < 0 || iz >= lighting.gridD) return 0u;
    uint idx = uint(iz * lighting.gridW * lighting.gridH + iy * lighting.gridW + ix);
    return grid.cells[idx] & 0x7Fu;
}

/// Get shadow triangle range for a grid cell.
/// Returns (startIndex, count) packed from bits 16-31 and 7-15.
void getShadowTriRange(int ix, int iy, int iz, out int start, out int count) {
    if (ix < 0 || ix >= lighting.gridW ||
        iy < 0 || iy >= lighting.gridH ||
        iz < 0 || iz >= lighting.gridD) {
        start = 0; count = 0; return;
    }
    uint idx = uint(iz * lighting.gridW * lighting.gridH + iy * lighting.gridW + ix);
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
        // No lights — just use base color (environment lighting already baked into v_color)
        outColor = vec4(baseColor, v_color.a);
        return;
    }

    // Offset world position along normal to avoid self-shadowing.
    // A generous offset pushes the ray origin well past the fragment's own
    // surface so that ray-triangle intersection min_t can reject nearby
    // self-hits while still catching occluder geometry.
    vec3 surfacePos = v_worldPos + N * 0.15;

    // Ambient minimum
    float ambient = 0.15;
    vec3 totalLight = vec3(ambient);

    for (int i = 0; i < numLights && i < 32; i++) {
        vec3 lightPos = lighting.lightPosIntensity[i].xyz;
        float intensity = lighting.lightPosIntensity[i].w;
        vec3 lightColor = lighting.lightColorRadius[i].rgb;
        float radius = lighting.lightColorRadius[i].a;

        vec3 toLight = lightPos - surfacePos;
        float distSq = dot(toLight, toLight);
        if (distSq < 0.001) continue;
        float dist = sqrt(distSq);

        // Skip if beyond radius
        if (dist > radius) continue;

        vec3 L = toLight / dist;
        float NdotL = max(dot(N, L), 0.0);
        if (NdotL <= 0.0) continue;

        // Shadow ray-march per pixel from offset surface position
        if (isOccluded(surfacePos, lightPos)) continue;

        float attenuation = intensity / (1.0 + distSq * 0.1);
        totalLight += lightColor * attenuation * NdotL;
    }

    totalLight = clamp(totalLight, vec3(0.0), vec3(1.0));
    outColor = vec4(baseColor * totalLight, v_color.a);
}
