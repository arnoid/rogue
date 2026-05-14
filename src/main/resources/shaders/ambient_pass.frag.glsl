#version 450

layout(location = 0) in vec3 v_normal;

layout(set = 0, binding = 2) uniform MaterialUBO {
    vec4 diffuseColor;
    vec4 emissiveColor;
    vec4 ambientColor;
} material;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = material.ambientColor * material.diffuseColor;
}
