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
