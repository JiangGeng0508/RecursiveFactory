# Recursive Factory

**English** · [简体中文](README.zh_CN.md)

Build a factory inside a block. Expand it in three dimensions, nest more factories inside, and connect everything through its walls.

**Minecraft 1.21.1 · NeoForge 21.1.249+ · Create 6.0.10+ · Java 21**

## What it does

| Feature | In game |
| --- | --- |
| Pocket factories | Each entrance opens a 16 × 16 × 16 room cell, with 14 × 14 × 13 clear space in a single cell. |
| Three-dimensional expansion | Place entrances beside, above or below one another to join their rooms. The room follows the entrance layout. |
| Nested factories | Build another factory inside a room; leaving each one returns you to the previous level. |
| Connections through walls | Transfer items, fluids, redstone and Create rotation between an entrance face and its matching room wall. No mode switching. |
| Live miniature | Entrance frames show their room contents at 1:16 scale, including entities and supported machines. |
| Copy and print | Capture factories with Create schematics and print their contents with the Schematicannon, including nested rooms. |
| Remote preview | Bind a thin preview display to an entrance and view that room cell from elsewhere, including another dimension. |

## Get started

1. Install this mod and Create on both the client and server.
2. Craft an entrance: **4 concrete blocks of one colour** in the corners, **4 obsidian** on the edges, and **1 Eye of Ender** in the centre. All 16 concrete colours work.
3. Place it and **right-click with an empty hand to enter**. Build your machines inside.
4. **Right-click a factory barrier to leave**, returning to where you entered.
5. Add adjacent entrances to expand, or place an entrance inside to start a nested factory.

Adjacent entrances share the factory's colour. Expansion is checked against the internal dimension bounds and other factories.

## Connect machines

A face selects the **same compass side** of the room: the north face connects to the north wall. Each entrance keeps its own room cell and channels after expansion.

- **Items / fluids:** connect a hopper, funnel, pipe or pump outside; place the receiving container directly against the matching inner wall. Connections work in both directions.
- **Rotation:** attach shafts or machines directly to the barrier wall and to the corresponding entrance face.
- **Redstone:** feed one end; the corresponding face at the other end outputs the signal.
- **Electricity, optional:** with **Create: Electro Energetics**, place a Connector against the entrance and the matching inner wall, then attach wires to the resulting surface nodes. Each face supports one node on each side; remove nodes with a Create wrench.

Use the middle of a wall, away from corners. Open boundaries between merged cells do not forward connections to another cell's outer wall. Create package logistics are not supported.

## Copy a factory

**Survival:** select the factory entrances with Create's **Schematic and Quill** and save the schematic. Deploy it, then load it into a **Schematicannon** with gunpowder and nearby material inventories. The cannon places the entrance first and builds the interior progressively. Missing materials pause the task; progress and the captured contents survive saving and reloading.

**Creative:** middle-click an entrance, then place the obtained block. It reads the source factory when placed and creates an independent copy, including its connected entrances and nested factories. Changes to the source are included in the next copy. A missing source or obstructed entrance layout prevents placement.

Copies preserve replaced floor tiles and floor holes. Unchanged default tiles are generated with the room and require no extra cannon materials. Right-clicking an entrance with a Blank Schematic no longer captures the factory. Factory capture supports up to **8 nested levels** and rejects loops. Installed CEE nodes and internal wires are included; connections leaving the captured room are excluded.

## Preview display

Hold a **Factory Preview** and right-click an entrance to bind it, then place it wherever you want to watch that room cell. The miniature appears directly above its thin base, keeps the source orientation and is visible from every side. It only displays the room.

Crafting: glass in the top centre; iron ingot, Eye of Ender, iron ingot in the middle row; iron ingot, smooth stone, iron ingot in the bottom row.

## Loading and configuration

Loaded entrances keep their rooms running. Players inside nested factories keep the entrance chain loaded; nearby preview viewers also keep the displayed room running. Temporary loading requests are released when no longer needed.

Settings live in `config/recursivefactory-common.toml`:

| Setting | Default | Purpose |
| --- | --- | --- |
| `room.repairBrokenFloor` | `false` | Restore missing checkerboard floor blocks when preparing a room. |
| `power.linkEnabled` | `true` | Enable CEE power transfer. |
| `power.linkResistance` | `10` | Resistance per end, in ohms. |
| `power.linkResponse` | `0.5` | Power link response per tick. |
| `power.linkMaxCurrent` | `1000` | Maximum requested current, in amps. |

Sable/Simulated integration adds previews of supported physics structures and ropes. These mods are optional. When upgrading from the old **32-block-high** room format, start a new world to avoid incompatible room geometry.

## Build

Start the test client in an existing development environment (Java 21 required):

```powershell
.\run-client.bat
```

The launcher uses Gradle's cached dependencies to avoid stalling on an unreachable Maven repository. It still compiles the current sources. Game files are in `run/client/`; the log is `run/client/logs/latest.log`.

On a fresh clone or after changing dependencies, run `.\gradlew.bat runClient --console=plain` online to download the required files. Use the same command if Gradle reports `No cached version ... available for offline mode`.

Build the distributable:

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

The jar is written to `build/libs/`. See [verification instructions](tools/verification/README.md) for the isolated server regression suite.

Entrance models, textures and preview rendering are original to this mod; the projection-preview approach follows the MIT-licensed Create: Pocket Factory project.
