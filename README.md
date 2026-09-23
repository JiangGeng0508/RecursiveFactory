# Recursive Factory

**English** | [中文](README.zh_CN.md)

Recursive Factory is a NeoForge mod for Minecraft 1.21.1. It provides a reusable pocket factory dimension with nested factory support.

## Features

- **Recursive factory block**: placing the block creates a dedicated factory room. Place another entrance block next to it and it grows the same factory instead of starting a new one, so a room can be as many cells big as you care to lay entrance blocks out.
- **Factory barrier**: the room's walls and ceiling. Every barrier block is an endpoint of the factory's link, so a hopper pointing at a barrier inside a room feeds the entrance block outside, and a signal fed to a barrier lights the entrance block's output face -- as long as the face that side of the room belongs to carries what you are sending (see the face modes below). Right-click a barrier to leave the room.
- **Everything crosses the wall**: items, fluids and Create's rotational force all go in and out through the barrier wall and the entrance block outside. Nothing has to pass through a doorway, because a room has none.
- **Mirrored item transport**: items inserted through NeoForge item handlers (including vanilla hoppers and Create logistics where supported) leave the opposite face of the linked endpoint.
- **Mirrored fluids**: the same link carries fluids, so a tank or a pump on one side fills a tank or feeds a pipe on the other. Create pipes, which never take anything themselves, are handed the fluid as they ask for it.
- **Mirrored redstone**: a signal fed to either end lights the other one, which then drives a single face -- the one the signal left through. Like a vanilla diode, nothing is emitted back at the input, so a relay can never power its own input and latch itself on.
- **Blueprints, a printer and copies of a factory**: right-click an entrance block with a **Mirror Factory Blueprint** and it reads the room out into a blueprint file, handing you the item that names it. Load that blueprint into a **Factory Printer** and the printer builds a room of its own with the same contents, one block at a time, out of the materials in the containers touching it. What comes out the other end is a **Mirror Factory** item: place it and an entrance block goes down on the printed room, so the copy is a factory like any other. Every further Mirror Factory item placed makes another copy.
- **A frame with six faces**: the entrance block is a frame one sixteenth of a block thick with its middle open, and the room's own miniature hangs in that opening. Each of its six faces carries exactly one thing, and the six do not disturb each other:

  | Face mode | What it carries |
  |---|---|
  | Transparent | nothing -- the face is a window |
  | Redstone | redstone, in and out |
  | Logistics | items, vanilla and Create |
  | Fluid | fluids |
  | Stress | Create's rotational force |

  A face names a **side of the room** rather than a direction of travel: set the north face to fluid and the room's north side is the fluid side. **Every face starts transparent**, so a new factory connects nothing at all until you open the faces you want. Sneak-right-click a face -- or right-click it with a Create wrench -- to move it on to the next mode, which is announced on your action bar; a bare right-click walks you in.
- **Six faces, six channels of rotation**: rotational force is carried per face too. Whichever stress face of the entrance block you drive outside turns only the matching wall inside, and the six faces do not disturb each other.
- **Output marker**: a lit relay marks the face it drives with a ring of glowing bars around that face of the frame, so you can see which way a signal is going.
- **One cell per entrance block**: each entrance block stands for one room cell. A cell is a 16x16x16 box of room in total -- a solid barrier base, a snow block/white concrete checkerboard floor, 14x14x13 of free space, and a solid barrier ceiling. The barrier shell sits on the chunk edge, so a cell's free space is 14x14. Walls follow the outline of the room, so a room that is not a rectangle is still sealed at its step, and merging two cells patches the floor where the wall between them used to stand.
- **Face preview**: the entrance block's face shows its own cell as a miniature at 1/16 scale, laid in the opening of the frame and lining up with it exactly, so the room never spills out of the block. The cell is sampled edge to edge, so its sixteen blocks cover the block's face exactly and the previews of two entrance blocks standing next to each other meet on the line between their blocks. The barrier shell is left out of the preview, so what you see through the frame is the room itself.
- **Entering and leaving**: right-click a recursive factory block to enter it; right-click any barrier from the inside to leave.

## Things to know

- **Worlds from before this version are not compatible.** Rooms are half as tall as they used to be (16 blocks instead of 32), so an old factory's room is rebuilt at the new height and the shell above it is taken down; anything you had built above the new ceiling is left standing outside it. Start a new world for this version.
- **A face must be switched on, and carries one kind of thing at a time.** Redstone, items, fluids and rotational force each need a face of their own -- a face set to one of them carries nothing else. Fresh entrance blocks have all six faces transparent, and there is one more mode than the four: `Transparent`, the window.
- **A room is a closed box.** Its walls, floor and ceiling are factory barriers; there is no door and no window. You leave by right-clicking any barrier from the inside, and you come back out where you went in.
- **The container that receives things must sit flush against the wall.** Items and fluids that come out of a wall land one block inside the room, on the side they entered through, and something that can take them has to be standing there. A chest a block away from the wall picks up nothing. The same goes outside: put the receiving container right next to the entrance block, on the face the items come out of.
- **A hopper or a pipe pump only needs to be on the input side.** You do not have to wrap the room in machinery: the side you feed is the side that answers, and one hopper or one pump is enough. If nothing on the far side can take what is being pushed, it waits in the endpoint's own buffer and the log says so -- build the receiving container and it drains.
- **Machines have to stand against a wall to join the rotational network.** A shaft, a gearbox or a machine bolted to a barrier block is on that wall's network. The floor is a checkerboard, not a barrier, so a machine standing free in the middle of the room turns nothing.
- **The corner column of a wall is not part of a link.** A corner block is wall on two sides at once, so there is no room behind it for anything to land in; each face of a single cell therefore answers on its fourteen middle columns. Machines built against a corner do not reach the link -- move them one block towards the middle.
- **One factory, one colour.** Entrance blocks placed next to an existing factory join it and take its colour rather than the colour of the item they were crafted from.
- **A printer pays for what it builds, in materials and in sugar.** For every block it places it takes one block's worth of items from the containers touching it, and one sugar pays for 400 blocks. It asks for the materials before it burns the sugar, so a printer waiting on a chest never runs its fuel down. Out of a material? The print stops on that block and carries on by itself once you feed it -- there is no need to start the room over.
- **Copying goes one room deep.** A factory that has grown past a single room cell is not copied; the blueprint item says so rather than half doing it. A factory whose blueprint is being read out holds still for that instant, and refuses to open while it lasts.
- **A blueprint is a file, not a stack of NBT.** It is written into the world's own `recursivefactory/blueprints` folder, in the same format as a Create schematic, so a room full of machinery never has to fit inside an item.
- **Create's contraption logistics is not hooked up.** Vanilla hoppers, chutes, funnels, item handlers, pipes and pumps work; package ports and contraption-mounted logistics do not.

## Usage

1. Craft and place a Recursive Factory block. Place more of them next to it to grow the room; the room follows the shape of the entrance blocks.
2. Set the faces you need: sneak-right-click a face, or right-click it with a Create wrench, to cycle it through transparent, redstone, logistics, fluid and stress. The action bar names the mode it landed on.
3. Right-click the block to enter its room.
4. Build inside the room. A barrier wall on a face you have opened is the item, fluid, redstone and rotational link back out.
5. Place another Recursive Factory block inside a room to create a nested factory. Exiting each level returns to the level that contained it.
6. Right-click a Factory Barrier to leave; you return to where you entered.
7. To copy a factory, right-click its entrance block with a Mirror Factory Blueprint. It reads the room out and hands you a blueprint item that names the file it wrote.
8. Put a Factory Printer down, ring it with containers of the materials the room is made of and some sugar, and right-click it with the blueprint. It prints the room one block at a time into a room of its own; right-click it with an empty hand to hear how far it has got and what it is waiting for. When it finishes, the copy goes into a container beside it, or drops on the floor.
9. Place the Mirror Factory item to put an entrance block down on the printed room. Place a second copy of the item and it copies that room again rather than sharing it.

## Configuration

`config/recursivefactory-common.toml`:

- `printer.delay` (default `10`): ticks the factory printer waits between the blocks it places. A room is 16 by 16 by 13, so a full one takes about half an hour at the default.
- `printer.shotsPerSugar` (default `400`): how many blocks one sugar is worth to the printer.
- `room.repairBrokenFloor` (default `false`): whether the blocks missing from a room's checkerboard floor are put back. Off, a room cell's floor is laid once, when the cell is first built, and a hole you dig stays a hole. On, every look at a room fills the missing floor blocks back in -- only the ones that are gone, so anything else you have standing on the floor layer is left alone. The barrier shell and the bedrock under the floor are put back either way.

## Development

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

The project targets Minecraft 1.21.1, NeoForge 21.1.249, and Create 6.0.10 or newer. The entrance model, texture, and projection renderer design are derived from the MIT-licensed Create: Pocket Factory reference project.