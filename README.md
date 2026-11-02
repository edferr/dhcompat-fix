# DH Compat Half-Construction Fix

An Iris patch mod for Minecraft 1.21.1 / NeoForge that repairs a Distant Horizons compat
instance which was cached while DH reported rendering disabled — the state that makes LODs
silently vanish for the lifetime of an Iris pipeline.

**Client-side only. Do not install this on a server.**

---

## The bug

`DHCompatInternal`'s constructor returns early when DH reports rendering disabled:

```
15: ifnull 45     // pipeline == null   -> return
42: ifne   46     // renderingEnabled   -> build programs and framebuffers
45: return        // FALSE              -> bail, incompatible stays false
```

That early return leaves `solidProgram`, `dhTerrainFramebuffer` and `genericShader` all null
while `incompatible` stays **false** — so the object looks healthy. Iris caches it in
`DHCompat.compatInternalInstance` for the life of the pipeline, and every later frame reads
that same empty instance. Iris believes DH is wired up; DH draws nothing.

Because the instance is cached, nothing recovers it. Disconnecting and reconnecting builds a
fresh pipeline and therefore a fresh instance, which is why a relog "fixes" it.

## What this mod does

Once per second, on the active pipeline:

1. Reads the pipeline's `DHCompatInternal` reflectively.
2. If it holds no programs, is not marked `incompatible`, **and** DH reports rendering
   enabled, rebuilds the instance and swaps it in.

The `renderingEnabled` gate is the important part: an empty instance while DH is switched
off is *correct* and is left alone. Only "empty while DH wants to draw" is a defect.

If a rebuild fails three times it sets `incompatible = true`, which makes Iris stop pretending
DH is wired up and lets DH fall back to its own unshaded renderer. Unshaded LODs beat no LODs.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Iris | **exactly** `1.8.14-beta.1+1.21.1` |
| Distant Horizons | 3.3.3 (optional at load, but the fix is pointless without it) |

The Iris requirement is enforced in `neoforge.mods.toml` as `[1.8.14-beta.1,1.8.15)`. This
patch is written against that release's constructor bytecode; on any other Iris the loader
refuses the jar rather than applying a patch to a shape that may have moved.

## Install

Drop the jar into your **client's** `mods/` folder and restart the game.

- CurseForge Launcher: your instance → *Open Folder* → `mods`
- Modrinth App: instance → *Open Folder* → `mods`

Nothing goes on the server. The server in this pack runs neither Iris nor Distant Horizons —
both are client-only renderers — so there is nothing there for this to patch. Adding it to a
server would do nothing and, because the mod declares no `displayTest`, could require every
client to have it in order to join.

## Uninstall

Delete the jar. That is the entire uninstall. It modifies no files, writes no config, and
registers no registry entries or network channels.

## Verifying it is working

There is one line per session when the mod is live:

```
[DHFIX] attached to pipeline <id> (dhRenderingEnabled=true) - watching for a stale compat instance
```

And one line if it ever repairs something:

```
[DHFIX] repaired stale DH compat on pipeline <id> (shadow=<bool>) - LODs restored
```

If LODs disappear and neither line is present, this mod is not the explanation. If the
"attached" line is present and no repair follows, the trigger is a stale state this rule does
not cover.

## Validation status — read this

The mod has been verified to **load, attach to a live Iris pipeline, and stay inert on a
healthy system** across 1,799 samples and 45 dimension-driven pipeline rebuilds.

It has **not** been observed repairing the bug. The trigger appears to be a Nether→overworld
crossing on a dedicated server, and the singleplayer test harness used to develop it does not
reproduce that state — 45 rebuilds produced zero stale instances. Treat this as a targeted
fix that needs real-world confirmation, not as a proven one.

## Cost

One reflective check per second: measured 20 ns per check, about **1.5 ms per hour** of play.
Nothing runs on the render path, and there is no mixin, so no other mod's classes are
transformed. A repair itself is heavier — it builds shader programs and framebuffers — and may
cause a brief stutter, at most three times per pipeline.

## Build

```bash
JAVA_HOME=/path/to/jdk21 ./gradlew build
# -> build/libs/iris-1.8.14-beta.1-dhcompatfix-<version>.jar
```

Requires a JDK 21. The jar name and the `mods.toml` version range both derive from
`iris_target` / `iris_version_range` in `gradle.properties`, so the lock cannot drift from the
filename.

## License

MIT
