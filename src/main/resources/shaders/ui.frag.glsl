#version 450

layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_texCoord;

layout(set = 0, binding = 0) uniform sampler2D fontTexture;

layout(location = 0) out vec4 outColor;

void main() {
    // If texCoord is (-1,-1) it's a solid color quad; otherwise sample font atlas
    if (v_texCoord.x < 0.0) {
        outColor = v_color;
    } else {
        float alpha = texture(fontTexture, v_texCoord).r;
        outColor = vec4(v_color.rgb, v_color.a * alpha);
    }
}
