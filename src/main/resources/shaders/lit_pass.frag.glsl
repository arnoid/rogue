// Lit pass fragment shader — per-light diffuse + inverse-square attenuation.
// No #version directive (prepended by Main.kt).

in vec3 v_worldPos;
in vec3 v_worldNormal;
in vec2 v_texCoord;

uniform vec3 u_LightPos;
uniform vec3 u_LightColor;
uniform float u_LightIntensity;
uniform float u_LightRadius;
uniform sampler2D u_diffuseTexture;
uniform vec4 u_diffuseColor;

out vec4 fragColor;

void main() {
    vec3 N = normalize(v_worldNormal);
    vec3 toLight = u_LightPos - v_worldPos;
    float dist = length(toLight);

    // Radius cutoff — no contribution beyond light radius.
    if (dist > u_LightRadius) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 L = toLight / max(dist, 0.0001);
    float NdotL = max(dot(N, L), 0.0);

    // Inverse-square attenuation with +1.0 to prevent divide-by-zero.
    float attenuation = u_LightIntensity / (dist * dist + 1.0);

    vec4 texColor = texture(u_diffuseTexture, v_texCoord);
    fragColor = texColor * u_diffuseColor * vec4(u_LightColor * attenuation * NdotL, 1.0);
}

