#version 450

// GPU-rasterized world rendering: 3D positions transformed by ViewProjection matrix
layout(location = 0) in vec3 a_worldPos;    // world-space vertex position
layout(location = 1) in vec4 a_color;       // base diffuse color
layout(location = 2) in vec3 a_normal;      // face normal

// Push constant: ViewProjection matrix
layout(push_constant) uniform PushConstants {
    mat4 viewProjection;
} pc;

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec3 v_worldPos;
layout(location = 2) out vec3 v_normal;

void main() {
    gl_Position = pc.viewProjection * vec4(a_worldPos, 1.0);
    v_color = a_color;
    v_worldPos = a_worldPos;
    v_normal = a_normal;
}

