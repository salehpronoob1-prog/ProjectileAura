# ProjectileAura (prototype) — Minecraft 1.21.11

Meteor addon source for **automatic aim at hostile mobs** using configurable arrow speed, gravity, drag, aim height, and target movement lead.

## Important status

**UNTESTED SOURCE PROTOTYPE, NOT A COMPILED MOD.** This environment cannot download Maven/Gradle dependencies, so API compatibility and successful compilation have not been verified. The Gradle version pins are starting guesses and may need adjustment to the actual 1.21.11 Meteor build. The Meteor template's current master branch targets Minecraft 26.3, not 1.21.11.

## Build

1. Install Java 21 and Gradle, or copy the official Gradle wrapper files from the Meteor addon template.
2. Run `gradle build` from this directory (or `./gradlew build` after adding the wrapper).
3. If dependencies fail, update `gradle/libs.versions.toml` to versions published for Minecraft 1.21.11.
4. If Minecraft mappings/API names differ, adapt the Java sources to the mapped API used by that Meteor build.
5. Put the resulting remapped addon JAR in `.minecraft/mods` with Fabric and matching Meteor.

## Usage

Enable `ProjectileAura` in Meteor ClickGUI. Start with `arrow-speed = 1.5`. The module currently rotates your view toward the nearest hostile mob; **it does not fire, measure actual projectile speed, or target player entities**. This is intentional for a testable first milestone. Test with stationary targets before moving ones. It does not alter Meteor's built-in KillAura or Bow Aimbot.

## Known limitations

- Client-side rotations may be overwritten by other aim modules.
- The numerical solver is approximate and assumes vanilla-like drag and gravity.
- Server-side custom arrow mechanics may not match these assumptions.
- Target selection uses generic hostile mobs and may miss custom bosses represented by other entity types.
- Does not compensate for latency, server tick variance, or obstacles.
