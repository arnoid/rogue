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
layout(set = 0, binding = 1) readonly buffer OccupancyGrid {
    uint cells[];
} grid;

layout(location = 0) out vec4 outColor;

/// Get wall flags for grid cell (ix,iy,iz)
uint getWallFlags(int ix, int iy, int iz) {
    if (ix < 0 || ix >= lighting.gridW ||
        iy < 0 || iy >= lighting.gridH ||
        iz < 0 || iz >= lighting.gridD) return 0u;
    uint idx = uint(iz * lighting.gridW * lighting.gridH + iy * lighting.gridW + ix);
    return grid.cells[idx];
}

/// Ray-march through the voxel grid from 'from' to 'to' using 3D DDA.
/// Checks wall edges (thin planes on cell boundaries) instead of full cell occupancy.
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
                // Crossing Z boundary — no vertical wall edges
                iz += sz; tMaxZ += tDeltaZ;
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
                // Crossing Z boundary — no vertical wall edges
                iz += sz; tMaxZ += tDeltaZ;
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
    // Surface pixels sit exactly on the boundary of an occupied cell,
    // so we push the shadow ray origin slightly outward.
    vec3 surfacePos = v_worldPos + N * 0.05;

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
