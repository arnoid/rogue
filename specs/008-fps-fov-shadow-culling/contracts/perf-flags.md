# Contract: PerfFlags

> A single feature-flag object governing every behaviour change made
> by spec 008. One toggle, one keybinding, one persistence key.

## Object

```kotlin
package com.roguelike.core.perf

object PerfFlags {
    @Volatile var enabled: Boolean
    @Volatile var centreFraction: Float
    const val PCF_TAPS_LOW: Int = 1
    const val MAX_PER_PIXEL_LIGHTS_LOW: Int = 3
}
```

## Default value

`enabled = true`. End-users get the perf gains without configuration.

## Persistence

| Key | File | Type | Read at | Written at |
|---|---|---|---|---|
| `perf.flags.enabled` | `local.properties` | `Boolean` | `Main.start()` (once) | Never (read-only override) |

The runtime F11 toggle does **not** write back to `local.properties`.
This is intentional: capture sessions need a hard, file-driven default
that survives crashes; mid-session experimentation is ephemeral.

## Keybinding

- **F11** — toggles `enabled` (one frame of latency acceptable).
- Implementation: hook into `InputSystem` exactly once at app start;
  call site in `Main.kt`'s update loop.
- Conflict check (must run before merge):
  ```powershell
  Select-String -Path src\main\kotlin -Recurse -Pattern "GLFW_KEY_F11|KEY_F11|VK_F11" |
      Format-Table Path,LineNumber,Line
  ```
  Today returns zero hits — F11 is unused.

## HUD reflection

The `[Profile]` HUD line gains a `driver=` suffix whose value is one
of `disabled | steady | gpu_bound | upload_spike | cache_miss`.
`disabled` appears IFF `PerfFlags.enabled == false`. This makes A/B
captures self-labelling.

## Test obligations

1. **Default**: `assertTrue(PerfFlags.enabled)`. Trivial; protects
   against accidental flips during refactor.
2. **Override**: a test that writes
   `perf.flags.enabled=false` into a tmp `local.properties`, boots a
   bare `PerfFlags.loadFromLocalProperties(file)`, asserts
   `PerfFlags.enabled == false`.
3. **Toggle latency**: a unit test that sets `enabled = false`,
   simulates one frame, asserts the `RoguelikeGame` perf path is
   bypassed (via a verifiable side effect, e.g. tile-quality SSBO
   filled with `2`s).

## Invariants

- `PerfFlags` MUST be the only source of truth for spec-008 toggling.
  No other class may add its own `enableTileLod`, `enableFrustumCull`,
  `enableHysteresis` boolean. Three knobs is exactly the failure mode
  this contract prevents.
- The toggle MUST be cheap (volatile read; no synchronisation block).
- Hot-toggling MUST NOT crash: every change site reads `enabled`
  once per frame, never inside a tight loop.

## Future-proofing

If a future spec needs to enable subsets of these changes (e.g.
"frustum cull only, no LOD"), add named accessors to `PerfFlags`:

```kotlin
val tileLodEnabled get() = enabled && /* possibly extra subflag */
val frustumCullEnabled get() = enabled && /* … */
```

…and route call sites through the named accessors. Do **not** add a
parallel singleton.

