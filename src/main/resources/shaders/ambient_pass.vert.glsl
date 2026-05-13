// Ambient pass vertex shader.
// No #version directive (prepended by Main.kt).

in vec3 a_position;
in vec3 a_normal;
in vec2 a_texCoord0;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

out vec2 v_texCoord;

void main() {
    v_texCoord = a_texCoord0;
    gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);
}

