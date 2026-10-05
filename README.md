# Recursive Factory

**English** | [中文](README.zh_CN.md)

Recursive Factory is a NeoForge mod for Minecraft 1.21.1. It provides a reusable pocket factory dimension with nested factory support.

## Features

- **Recursive factory block**: placing the block creates a dedicated factory room. Place another entrance block next to it -- or directly above or below it -- and it grows the same factory instead of starting a new one, so a room can be as many cells big as you care to lay entrance blocks out, stacked vertically if you like.
- **Factory barrier**: the room's walls and ceiling. Every barrier block is an endpoint of the factory's link, so a hopper pointing at a barrier inside a room feeds the entrance block outside, and a signal fed to a barrier lights the entrance block's output face. Right-click a barrier to leave the room.
- **Everything crosses the wall**: items, fluids and Create's rotational force all go in and out through the barrier wall and the entrance block outside. Nothing has to pass through a doorway, because a room has none.
- **Mirrored item transport**: items inserted through NeoForge item handlers (including vanilla hoppers and Create logistics where supported) leave the opposite face of the linked endpoint.
- **Mirrored fluids**: the same link carries fluids, so a tank or a pump on one side fills a tank or feeds a pipe on the other. Create pipes, which never take anything themselves, are handed the fluid as they ask for it.
- **Mirrored redstone**: a signal fed to either end lights the other one, which then drives a single face -- the one the signal left through. Like a vanilla diode, nothing is emitted back at the input, so a relay can never power its own input and latch itself on.
- **Print factories with Create's Schematicannon**: capture an entrance with a Blank Schematic or select it with Schematic and Quill, deploy the schematic, and load it into a Schematicannon. The cannon places the entrance and empty room shell first, then prints the contents one placement at a time using adjacent inventories. Nested factories print progressively too. Expanded factories share one interior task, while each entrance binds its own corresponding room cell.
- **A frame with six faces, each carrying everything at once**: the entrance block is a frame one sixteenth of a block thick with its middle open, and the room's own miniature hangs in that opening. A face names a **side of the room** rather than a direction of travel: connect an item handler to the north face and the room's north side is where the items come out. There is nothing to switch on -- items, fluids, redstone and rotational force each find their own way through a face and do not disturb each other, so one face can carry a hopper, a pipe and a signal at the same time. A bare right-click walks you in; sneak-right-click sets nothing, and a Create wrench still turns the block.
- **Electricity, when Create: Electro Energetics is installed**: place a CEE **Connector** against an entrance face and against the matching room wall. Each connector becomes a surface node; connect your wires to those nodes to transfer power in either direction. Each entrance face accepts one node outside and one inside its corresponding room cell. Adjacent entrances can each have a node on the same face. Unused faces have no nodes. Remove a node with a Create wrench before installing it elsewhere. Existing surface nodes and wires are preserved when upgrading.
- **Every entrance keeps its own connections after expansion.** Items, fluids, redstone, stress and electricity connect each entrance to its corresponding 16×16 room cell. Wall sections on the same side of different cells remain independent. Internal cell boundaries stay open; they do not forward connections to another cell's outer wall.
- **Six faces, six channels of rotation**: rotational force is carried per face too. Whichever stress face of the entrance block you drive outside turns only the matching wall inside, and the six faces do not disturb each other.
- **One cell per entrance block**: each entrance block stands for one room cell. A cell is a 16x16x16 box of room in total -- a solid barrier base, a snow block/white concrete checkerboard floor, 14x14x13 of free space, and a solid barrier ceiling. The barrier shell sits on the chunk edge, so a cell's free space is 14x14. Walls follow the outline of the room, so a room that is not a rectangle is still sealed at its step, and merging two cells patches the floor where the wall between them used to stand. Cells stacked above or below one another join into the same room, opening the wall between them layer to layer.
- **Face preview**: the entrance block's face shows its own cell as a miniature at 1/16 scale, laid in the opening of the frame and lining up with it exactly, so the room never spills out of the block. The cell is sampled edge to edge, so its sixteen blocks cover the block's face exactly and the previews of two entrance blocks standing next to each other meet on the line between their blocks. The barrier shell is left out of the preview, so what you see through the frame is the room itself.
- **Entering and leaving**: right-click a recursive factory block to enter it; right-click any barrier from the inside to leave.

## Things to know

- **Worlds from before this version are not compatible.** Rooms are half as tall as they used to be (16 blocks instead of 32), so an old factory's room is rebuilt at the new height and the shell above it is taken down; anything you had built above the new ceiling is left standing outside it. Start a new world for this version.
- **Faces need no mode switches.** Items, fluids, redstone and rotational force can share a face; electricity uses the installed surface nodes.
- **A room is a closed box.** Its walls, floor and ceiling are factory barriers; there is no door and no window. You leave by right-clicking any barrier from the inside, and you come back out where you went in.
- **The container that receives things must sit flush against the wall.** Items and fluids that come out of a wall land one block inside the room, on the side they entered through, and something that can take them has to be standing there. A chest a block away from the wall picks up nothing. The same goes outside: put the receiving container right next to the entrance block, on the face the items come out of.
- **A hopper or a pipe pump only needs to be on the input side.** You do not have to wrap the room in machinery: the side you feed is the side that answers, and one hopper or one pump is enough. If nothing on the far side can take what is being pushed, it waits in the endpoint's own buffer and the log says so -- build the receiving container and it drains.
- **Machines have to stand against a wall to join the rotational network.** A shaft, a gearbox or a machine bolted to a barrier block is on that wall's network. The floor is a checkerboard, not a barrier, so a machine standing free in the middle of the room turns nothing.
- **The corner column of a wall is not part of a link.** A corner block is wall on two sides at once, so there is no room behind it for anything to land in; each face of a single cell therefore answers on its fourteen middle columns. Machines built against a corner do not reach the link -- move them one block towards the middle.
- **One factory, one colour.** Entrance blocks placed next to an existing factory join it and take its colour rather than the colour of the item they were crafted from.
- **Pay as each placement happens.** Blocks, terminals and wires consume their materials and cannon fuel per step, using Create's firing delay and gunpowder settings. Missing materials pause the job without burning fuel; refilling resumes it. Skip-missing and Creative Crates are supported. The cannon saves its progress and factory snapshots, so changes to the original factory do not change a running job.
- **Factories nested inside come along.** An entrance block standing inside a room leads into a factory of its own, and the blueprint follows it: that factory is read out too, and the cannon builds it as a room of its own with the copied entrance block wired into the new one, so a copy never shares machinery with the original. A factory inside that one is followed the same way, eight levels deep at most. A factory that has grown past a single room cell is copied as it stands, cells and all. Two things stop a capture with a message on your action bar rather than half a copy: a chain of entrances deeper than eight levels, and a chain of entrances leading back into a factory that is already being read out. A factory whose blueprint is being read out holds still for that instant, and refuses to open while it lasts.
- **Use Create's tools and files.** Schematics live in `schematics/uploaded/<player>`. The cannon's checklist and progress include internal tasks. Internal blocks are placed directly in the factory dimension, without cross-dimensional projectile animation. Creative deployment can still place the complete factory instantly.
- **CEE wires come along.** Entrance previews show the room's cables. Factory blueprints copy internal wires, surface nodes, wire attachments and catenary connections, including those in nested rooms. The cannon requires their materials; connections leaving the factory are excluded. Blueprint-and-quill selections preserve entrance terminals, labels and their wires when both endpoint blocks are selected. The Schematicannon consumes connectors, wire and attachment materials once and connects the wire after both endpoints are placed. Recapture older exported blueprints to include these connections.
- **Rooms and entrances load on demand.** Loaded entrances keep their rooms running. While a player is inside a factory, all its bound entrance chunks stay loaded too; entrances inside another factory keep that factory's entrances loaded in turn, all the way to the outermost world. Players share these temporary requests. Once the last relevant player leaves or disconnects, the requests are released and chunks without another loading source unload normally.
- **Create's contraption logistics is not hooked up.** Vanilla hoppers, chutes, funnels, item handlers, pipes and pumps work; package ports and contraption-mounted logistics do not.

## Usage

With Sable installed, entrance previews also show physics structures in the room, including their movement and rotation. Assembling or disassembling entrance blocks preserves their factory and room cells. Rooms containing loaded physics structures stay active while those structures remain there.

In multiplayer, entrance previews show other players in the room, with their skins, equipment, position and pose. Spectators and your own player are hidden from the preview.

Mobs in an unoccupied factory room are protected from distance despawning caused by players in other rooms. This also applies to mobs summoned by command blocks; Peaceful difficulty still removes hostile mobs.

1. Craft and place a Recursive Factory block. Place more of them next to it to grow the room; the room follows the shape of the entrance blocks.
2. Nothing has to be opened or switched on: whatever you put against a face -- a hopper, a pipe, a shaft, a redstone signal, or a Create: Electro Energetics terminal -- goes through to the matching wall of the room.
3. Right-click the block to enter its room.
4. Build inside the room. Any barrier wall is the item, fluid, redstone and rotational link back out.
5. Place another Recursive Factory block inside a room to create a nested factory. Exiting each level returns to the level that contained it.
6. Right-click a Factory Barrier to leave; you return to where you entered.
7. To copy a factory, right-click its entrance block with a Blank Blueprint (Create's, made from paper). It reads the room out -- the factories standing inside it included -- and hands you a blueprint item that names the file it wrote.
8. Deploy the schematic, place material containers next to a Schematicannon, load the schematic and gunpowder, and start printing. The entrance appears first; its contents are built progressively. View progress and missing materials in the cannon interface.
9. Terminals and wires are restored as their supports become available. The old standalone Factory Printer has been removed; existing Mirror Factory items remain usable.

In Creative mode, middle-click a factory entrance (Pick Block) to obtain a factory block with its contents; Ctrl is not required. Its item form is drawn as a full cube from an isometric view, like an ordinary block.
The copy preserves the captured colour, expanded layout, inventory contents and nested factories. It records every entrance block the layout grew through, so an expanded factory is placed back as a whole; if another block already stands in any of those positions, placement is refused with a message on your action bar and nothing is placed. Each placement creates an independent factory, even after the original is changed or removed.

## Configuration

`config/recursivefactory-common.toml`:

- `power.linkEnabled` (default `true`): whether the two ends of a face's electrical link are tied together. Only matters with Create: Electro Energetics installed; off, every terminal is left to itself.
- `power.linkResistance` (default `10`): the series resistance, in ohms, of each end of an electrical link.
- `power.linkResponse` (default `0.5`): how much of the way an electrical link moves towards its new setpoint each tick.
- `power.linkMaxCurrent` (default `1000`): the most current, in amperes, one end of a link will push through itself.
- `room.repairBrokenFloor` (default `false`): whether the blocks missing from a room's checkerboard floor are put back. Off, a room cell's floor is laid once, when the cell is first built, and a hole you dig stays a hole. On, every look at a room fills the missing floor blocks back in -- only the ones that are gone, so anything else you have standing on the floor layer is left alone. The barrier shell and the bedrock under the floor are put back either way.

## Development

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

The project targets Minecraft 1.21.1, NeoForge 21.1.249, and Create 6.0.10 or newer. Create: Electro Energetics is an **optional** dependency: the surface-node integration is only enabled when that mod is installed, and the build only needs its API on the compile classpath (`compileOnly`). The entrance model, texture, and projection renderer design are derived from the MIT-licensed Create: Pocket Factory reference project.
