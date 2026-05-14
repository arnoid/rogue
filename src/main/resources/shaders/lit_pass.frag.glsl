#version 450

layout(location = 0) in vec3 v_normal;
layout(location = 1) in vec3 v_worldPos;

layout(set = 0, binding = 1) uniform LightUBO {
    vec3 lightPosition;
    float intensity;
    vec4 lightColor;
    float radius;
} light;

layout(set = 0, binding = 2) uniform MaterialUBO {
    vec4 diffuseColor;
    vec4 emissiveColor;
    vec4 ambientColor;
} material;

// Occluder triangles SSBO: each triangle is 3 vec4s (xyz used, w unused for padding)
layout(set = 0, binding = 3) readonly buffer OccluderSSBO {
    uint triangleCount;
    uint _pad0;
    uint _pad1;
    uint _pad2;
    vec4 triangles[]; // 3 vec4s per triangle: v0, v1, v2
} occluders;

layout(location = 0) out vec4 outColor;

// Möller–Trumbore ray-triangle intersection
bool rayTriangleIntersect(vec3 orig, vec3 dir, vec3 v0, vec3 v1, vec3 v2, float maxDist) {
    const float EPSILON = 1e-6;
    vec3 edge1 = v1 - v0;
    vec3 edge2 = v2 - v0;
    vec3 h = cross(dir, edge2);
    float a = dot(edge1, h);
    if (abs(a) < EPSILON) return false;

    float f = 1.0 / a;
    vec3 s = orig - v0;
    float u = f * dot(s, h);
    if (u < 0.0 || u > 1.0) return false;

    vec3 q = cross(s, edge1);
    float v = f * dot(dir, q);
    if (v < 0.0 || u + v > 1.0) return false;

    float t = f * dot(edge2, q);
    return (t > EPSILON && t < maxDist);
}

bool isInShadow(vec3 worldPos, vec3 lightPos) {
    vec3 toLight = lightPos - worldPos;
    float dist = length(toLight);
    if (dist < 0.001) return false;
    vec3 dir = toLight / dist;
    // Offset origin slightly along normal to avoid self-shadowing
    vec3 origin = worldPos + dir * 0.01;
    float maxDist = dist - 0.02; // Don't intersect at the light itself

    uint count = occluders.triangleCount;
    for (uint i = 0; i < count; i++) {
        uint base = i * 3;
        vec3 v0 = occluders.triangles[base + 0].xyz;
        vec3 v1 = occluders.triangles[base + 1].xyz;
        vec3 v2 = occluders.triangles[base + 2].xyz;
        if (rayTriangleIntersect(origin, dir, v0, v1, v2, maxDist)) {
            return true;
        }
    }
    return false;
}

void main() {
    vec3 N = normalize(v_normal);
    vec3 toLight = light.lightPosition - v_worldPos;
    float dist = length(toLight);

    if (dist > light.radius) {
        outColor = vec4(0.0);
        return;
    }

    // Per-pixel shadow test
    if (isInShadow(v_worldPos, light.lightPosition)) {
        outColor = vec4(0.0);
        return;
    }

    vec3 L = toLight / max(dist, 0.0001);
    float NdotL = max(dot(N, L), 0.0);
    float attenuation = light.intensity / (dist * dist + 1.0);

    outColor = material.diffuseColor * vec4(light.lightColor.rgb * attenuation * NdotL, 1.0);
}
