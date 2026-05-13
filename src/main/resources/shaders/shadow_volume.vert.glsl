// Shadow volume vertex shader — position-only geometry.
// No #version directive (prepended by Main.kt).

in vec3 a_position;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

void main() {
    gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);
}

