#version 450

layout(location = 0) in vec2 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec2 a_texCoord;

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_texCoord;

void main() {
    // a_position is in normalized device coords (-1 to 1)
    gl_Position = vec4(a_position, 0.0, 1.0);
    v_color = a_color;
    v_texCoord = a_texCoord;
}
