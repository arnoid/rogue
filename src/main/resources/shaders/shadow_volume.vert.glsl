#version 450

layout(location = 0) in vec3 a_position;

layout(set = 0, binding = 0) uniform SceneUBO {
    mat4 viewProjection;
    vec3 cameraPosition;
    float _pad0;
} scene;

layout(push_constant) uniform PushConstants {
    mat4 modelMatrix;
} push;

void main() {
    gl_Position = scene.viewProjection * push.modelMatrix * vec4(a_position, 1.0);
}
