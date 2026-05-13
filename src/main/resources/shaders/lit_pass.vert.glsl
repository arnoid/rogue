// Lit pass vertex shader — outputs world position and normal for lighting.
// No #version directive (prepended by Main.kt).

in vec3 a_position;
in vec3 a_normal;
in vec2 a_texCoord0;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

out vec3 v_worldPos;
out vec3 v_worldNormal;
out vec2 v_texCoord;

void main() {
    vec4 worldPos = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    v_worldNormal = mat3(u_worldTrans) * a_normal;
    v_texCoord = a_texCoord0;
    gl_Position = u_projViewTrans * worldPos;
}

