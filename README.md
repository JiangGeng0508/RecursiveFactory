# Recursive Factory

Recursive Factory is a NeoForge mod for Minecraft 1.21.1. It provides a reusable one-chunk pocket factory dimension with nested factory support.

## Features

- **Recursive factory block**: placing the block creates a dedicated factory room. The same block can be placed inside a factory to create another nested level.
- **Mirror factory block**: generated at the center of every factory room and can also be crafted. It is bound to the matching recursive factory block.
- **Mirrored item transport**: items inserted through NeoForge item handlers (including vanilla hoppers and Create logistics where supported) leave the opposite face of the bound endpoint.
- **Mirrored redstone**: weak redstone is mirrored bidirectionally, while strong/direct signal is emitted from the opposite face.
- **Bidirectional view**: the internal mirror renders the external world, while the external recursive factory block renders its factory room.
- **One-chunk room**: the floor is a snow block/white concrete checkerboard. Leaving the chunk exits to the position that entered that factory level.
- **Directional entry**: sneak near the external recursive factory block to enter from the corresponding side.

## Usage

1. Craft and place a Recursive Factory block.
2. Right-click it or sneak nearby to enter.
3. Build inside the one-chunk room. Use the central Mirror Factory as an item/redstone bridge and outside-world viewer.
4. Right-click the Mirror Factory to exit, or walk outside the room's chunk boundary.
5. Place another Recursive Factory block inside a room to create a nested factory. Exiting each level returns to the level that contained it.

## Development

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

The project targets Minecraft 1.21.1, NeoForge 21.1.249, and Create 6.0.10 or newer. The entrance model, texture, and projection renderer design are derived from the MIT-licensed Create: Pocket Factory reference project.

### Immersive Portals branch

This branch additionally requires Immersive Portals 6.0.7 and Cloth Config 15.0.140.
Before the first build, download `immersive_portals-6.0.7-all.jar` from the
[NeoForge release](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.7)
into `.reference/immersive-portals/`. Gradle supplies Create, its libraries and Cloth Config.

Start the development client with `gradlew.bat runClient --console=plain`, or use
`run-client.bat` when the dependencies are already cached (offline mode).
All development runs use `run/immersive-portals/<run-name>/`; the client stores its
mods, saves and configuration in `run/immersive-portals/client/`.
Existing `run/client/` and `run/server/` directories are left intact for the other branch.

The separate directories prevent the portal branch from loading the other branch's mod stack.
Sable 2.0.5 (including the stack used by Create Aeronautics 1.3.2) conflicts with
Immersive Portals 6.0.7's player collision mixin and crashes during startup.
Sodium 0.8.13 also changes classes targeted by that version of Immersive Portals.
Do not copy the entire old `mods` directory into the portal profile; verify additional
mods individually. Required Gradle dependencies must not also be copied into `mods`.
