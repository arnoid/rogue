in vec3 a_position;
in vec3 a_normal;
in vec2 a_texCoord0;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;
uniform mat3 u_normalMatrix;
uniform mat4 u_shadowMapProjViewTrans;

out vec2 v_texCoord;
out vec3 v_worldPos;
out vec3 v_worldNormal;
out vec4 v_shadowCoord;

void main() {
    vec4 worldPos4 = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos    = worldPos4.xyz;
    v_worldNormal = normalize(u_normalMatrix * a_normal);
    v_texCoord    = a_texCoord0;
    v_shadowCoord = u_shadowMapProjViewTrans * worldPos4;
    gl_Position   = u_projViewTrans * worldPos4;
}
