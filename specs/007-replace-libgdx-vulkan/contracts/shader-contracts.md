# Shader Contracts

## SPIR-V Shader Interface Contracts

All shaders target Vulkan GLSL (`#version 450`).

### Vertex Input Bindings

**Binding 0 — Standard Vertex** (used by ambient + lit passes):
```glsl
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_texCoord0;  // optional
```
Stride: 32 bytes (pos=12, normal=12, uv=8) or 24 bytes without UV.

**Binding 0 — Position-Only Vertex** (used by shadow volume pass):
```glsl
layout(location = 0) in vec3 a_position;
```
Stride: 12 bytes.

### Descriptor Set Layout

```
Set 0:
  Binding 0: UBO — SceneUBO { mat4 viewProjection; vec3 cameraPos; }
  Binding 1: UBO — LightUBO { vec3 position; float intensity; vec4 color; float radius; }
  Binding 2: UBO — MaterialUBO { vec4 diffuse; vec4 emissive; vec4 ambient; }

Push Constants (vertex stage):
  mat4 modelMatrix (64 bytes, offset 0)
```

### ambient_pass.vert
```
Input:  a_position, a_normal
Output: v_normal (vec3)
UBOs:   SceneUBO (binding 0)
Push:   modelMatrix
```

### ambient_pass.frag
```
Input:  v_normal
Output: outColor (location 0)
UBOs:   MaterialUBO (binding 2) — uses ambientColor * diffuseColor
```

### shadow_volume.vert
```
Input:  a_position (position-only)
Output: gl_Position
UBOs:   SceneUBO (binding 0)
Push:   modelMatrix (identity for shadow volumes)
```

### shadow_volume.frag
```
Input:  none
Output: none (color write disabled via pipeline state)
```

### lit_pass.vert
```
Input:  a_position, a_normal
Output: v_normal (vec3), v_worldPos (vec3)
UBOs:   SceneUBO (binding 0)
Push:   modelMatrix
```

### lit_pass.frag
```
Input:  v_normal, v_worldPos
Output: outColor (location 0) — additive blended
UBOs:   LightUBO (binding 1), MaterialUBO (binding 2)
Computation: diffuse = max(dot(N, L), 0) * attenuation * lightColor * intensity * diffuseColor
```

## Build Integration

Gradle task `compileShaders`:
- Input: `src/main/resources/shaders/*.vert`, `*.frag`
- Output: `build/resources/main/shaders/*.spv`
- Tool: shaderc via LWJGL bindings (Kotlin script) or external `glslc`
- Fail build on any compilation error


