// Ambient pass fragment shader.
// No #version directive (prepended by Main.kt).

in vec2 v_texCoord;

uniform vec3 u_ambientColor;
uniform sampler2D u_diffuseTexture;
uniform vec4 u_diffuseColor;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(u_diffuseTexture, v_texCoord);
    fragColor = texColor * u_diffuseColor * vec4(u_ambientColor, 1.0);
}

