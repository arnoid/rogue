in vec2 v_texCoord;
in vec3 v_worldPos;
in vec3 v_worldNormal;
in vec4 v_shadowCoord;

uniform sampler2D u_diffuseTexture;

// Ambient
uniform vec3 u_ambientColor;

// Directional light + shadow map
uniform int       u_hasDirLight;
uniform vec3      u_dirLightDir;
uniform vec3      u_dirLightColor;
uniform sampler2D u_shadowMap;

// Point lights (up to 8)
uniform int   u_pointLightCount;
uniform vec3  u_pointLightPos[8];
uniform vec3  u_pointLightColor[8];
uniform float u_pointLightIntensity[8];

// Point light omnidirectional shadow cubemaps
uniform int         u_hasPointShadow[8];
uniform samplerCube u_pointShadowCube[8];
uniform float       u_pointShadowFarPlane[8];

// Base color for the current renderable.
// For texture-based materials this is (1,1,1,1); for ColorAttribute materials
// the tile/item color is passed here and sampled against a white fallback texture.
uniform vec4 u_diffuseColor;

// PCF 3x3 directional shadow test. Returns 0.0 (lit) or 1.0 (shadowed).
float directionalShadow() {
    if (u_hasDirLight == 0) return 0.0;

    vec3 proj = v_shadowCoord.xyz / v_shadowCoord.w;
    // Map from [-1,1] to [0,1] for texture lookup.
    proj = proj * 0.5 + 0.5;

    if (proj.z > 1.0 || proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0)
        return 0.0;

    float currentDepth = proj.z;
    float bias = 0.005;
    float shadow = 0.0;
    vec2 texelSize = 1.0 / vec2(textureSize(u_shadowMap, 0));
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float pcfDepth = texture(u_shadowMap, proj.xy + vec2(x, y) * texelSize).r;
            shadow += (currentDepth - bias > pcfDepth) ? 1.0 : 0.0;
        }
    }
    return shadow / 9.0;
}

// GLSL 1.50 forbids dynamic indexing into sampler arrays; use a constant-index ladder.
float samplePointShadowCube(int i, vec3 dir) {
    if (i == 0) return texture(u_pointShadowCube[0], dir).r;
    if (i == 1) return texture(u_pointShadowCube[1], dir).r;
    if (i == 2) return texture(u_pointShadowCube[2], dir).r;
    if (i == 3) return texture(u_pointShadowCube[3], dir).r;
    if (i == 4) return texture(u_pointShadowCube[4], dir).r;
    if (i == 5) return texture(u_pointShadowCube[5], dir).r;
    if (i == 6) return texture(u_pointShadowCube[6], dir).r;
    return texture(u_pointShadowCube[7], dir).r;
}

// Cubemap omnidirectional shadow test for a point light.
float pointShadow(int i, vec3 fragToLight, float dist) {
    if (u_hasPointShadow[i] == 0) return 0.0;
    float closestDepth = samplePointShadowCube(i, -fragToLight) * u_pointShadowFarPlane[i];
    float bias = 0.05;
    return (dist - bias > closestDepth) ? 1.0 : 0.0;
}

void main() {
    vec3 norm    = normalize(v_worldNormal);
    vec3 diffuse = vec3(0.0);

    // Directional light contribution.
    if (u_hasDirLight != 0) {
        vec3 lightDir = normalize(-u_dirLightDir);
        float diff    = max(dot(norm, lightDir), 0.0);
        float shadow  = directionalShadow();
        diffuse += u_dirLightColor * diff * (1.0 - shadow);
    }

    // Point light contributions.
    for (int i = 0; i < u_pointLightCount; i++) {
        vec3  fragToLight = u_pointLightPos[i] - v_worldPos;
        float dist        = length(fragToLight);
        vec3  lightDir    = fragToLight / dist;
        float diff        = max(dot(norm, lightDir), 0.0);
        float attenuation = u_pointLightIntensity[i] / (dist * dist + 1.0);
        float shadow      = pointShadow(i, fragToLight, dist);
        diffuse += u_pointLightColor[i] * diff * attenuation * (1.0 - shadow);
    }

    vec3 totalLight = u_ambientColor + diffuse;
    fragColor = texture(u_diffuseTexture, v_texCoord) * u_diffuseColor * vec4(totalLight, 1.0);
}
