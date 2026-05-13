// Shadow volume fragment shader — no-op (colour writes disabled at GL level).
// No #version directive (prepended by Main.kt).

out vec4 fragColor;

void main() {
    fragColor = vec4(0.0);
}

