#version 450

// Vertex attributes: screen-space pos, base color, world-space position, face normal
layout(location = 0) in vec2 a_position;    // NDC screen position
layout(location = 1) in vec4 a_color;       // base diffuse color (pre-multiplied with face shade)
layout(location = 2) in vec3 a_worldPos;    // world-space position of this vertex
layout(location = 3) in vec3 a_normal;      // face normal

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec3 v_worldPos;
layout(location = 2) out vec3 v_normal;

void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
    v_color = a_color;
    v_worldPos = a_worldPos;
    v_normal = a_normal;
}

