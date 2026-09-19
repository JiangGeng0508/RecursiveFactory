# Recursive Factory

Recursive Factory is a NeoForge mod for Minecraft 1.21.1. It provides a reusable pocket factory dimension with nested factory support.

## Features

- **Recursive factory block**: placing the block creates a dedicated factory room. Place another entrance block next to it and it grows the same factory instead of starting a new one, so a room can be as many cells big as you care to lay entrance blocks out.
- **Factory barrier**: the room's walls and ceiling. Every barrier block carries the factory's item and redstone link, so a hopper pointing at a barrier inside a room feeds the entrance block outside, and a signal fed to a barrier lights the entrance block's output face. Right-click a barrier to leave the room.
- **Mirrored item transport**: items inserted through NeoForge item handlers (including vanilla hoppers and Create logistics where supported) leave the opposite face of the linked endpoint.
- **Mirrored redstone**: a signal fed to either end lights the other one, which then drives a single face -- the one the signal left through. Like a vanilla diode, nothing is emitted back at the input, so a relay can never power its own input and latch itself on.
- **Output marker**: a lit relay marks the face it drives with a small glowing node, so you can see which way a signal is going.
- **One cell per entrance block**: each entrance block stands for one room cell. A cell is 16x16x32 of room in total -- a solid barrier base, a snow block/white concrete checkerboard floor, 14x14x29 of free space, and a solid barrier ceiling. The barrier shell sits on the chunk edge, so a cell's free space is 14x14. Walls follow the outline of the room, so a room that is not a rectangle is still sealed at its step, and merging two cells patches the floor where the wall between them used to stand.
- **Face preview**: the entrance block's face shows its own cell as a miniature at 1/16 scale. The cell is sampled edge to edge, so its sixteen blocks cover the block's face exactly and the previews of two entrance blocks standing next to each other meet on the line between their blocks. The barrier shell is left out of the preview, the room's floor sits on top of the block's own model, and the room -- far taller than it is wide -- reaches up out of the block.
- **Directional entry**: right-click a recursive factory block to enter it.

## Usage

1. Craft and place a Recursive Factory block.
2. Right-click it to enter its room.
3. Build inside the room. The barrier wall is the item and redstone bridge back out.
4. Place more Recursive Factory blocks next to the entrance to grow the room; the room follows the shape of the entrance blocks.
5. Place another Recursive Factory block inside a room to create a nested factory. Exiting each level returns to the level that contained it.
6. Right-click a Factory Barrier to leave; you return to where you entered.

## Development

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

The project targets Minecraft 1.21.1, NeoForge 21.1.249, and Create 6.0.10 or newer. The entrance model, texture, and projection renderer design are derived from the MIT-licensed Create: Pocket Factory reference project.
