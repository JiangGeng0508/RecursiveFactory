"""Generates the entrance block's frame models and its blockstate.

The block is a frame: twelve one sixteenth bars along the edges of the block with the middle open, so the
preview of the room shows through it (see RecursiveFactoryRenderer). Every face of every bar carries a tint
index for the direction it points in - FactoryColors.FACE_TINT_BASE + Direction#get3DDataValue() - which is
what draws a face of the frame in the color of that side's mode.

Every bar lies in the plane of each of the two sides of the room it touches, so a side that carries on into
the entrance block beside it - a factory that has grown past one cell - drops the four bars of that plane:
the plane is inside the room now, and a post there would show as a seam. That is why each bar is a model of
its own and the blockstate is a multipart: it asks for every bar whose sides are all still open.

A bar that runs up the block lies in two sides at once, and is drawn only when both of them are open: the
bars at the four corners of a side belong to that side, so a joined side takes its corners with it and two
entrance blocks in a row show posts at the two ends and nowhere in between. The bars along the top and the
bottom edge of a side have only that one side to ask about - a room has nothing above or below it, so those
edges are drawn whenever the side is.

What a block state cannot see is the cell diagonally across from it, which is what tells a corner of a room
from a point in the middle of a straight wall. A room whose cells turn a corner therefore keeps a gap in the
frame at the inside of that corner. A straight run or a rectangle has no such corner, and is exact.

Run from the repository root:  python tools/gen_frame_models.py
"""
import io
import json
import os

MODELS = "src/main/resources/assets/recursivefactory/models/block"
BLOCKSTATES = "src/main/resources/assets/recursivefactory/blockstates"
NAMESPACE = "recursivefactory"
FRAME_TEXTURE = NAMESPACE + ":block/recursive_factory"
MARKER_TEXTURE = NAMESPACE + ":block/relay_output"
FACE_TINT_BASE = 100

# Direction#get3DDataValue(), the order the face modes are stored and asked about in.
DIRS = ["down", "up", "north", "south", "west", "east"]
AXES = ("x", "y", "z")
# Which side of the room the low and the high end of each axis is.
LOW = {"x": "west", "y": "down", "z": "north"}
HIGH = {"x": "east", "y": "up", "z": "south"}

# The twelve edges of the block, as boxes: a one sixteenth square running the whole way along one axis.
FRAME = [
    [0, 0, 0, 16, 1, 1], [0, 0, 15, 16, 1, 16], [0, 15, 0, 16, 16, 1], [0, 15, 15, 16, 16, 16],
    [0, 0, 0, 1, 16, 1], [0, 0, 15, 1, 16, 16], [15, 0, 0, 16, 16, 1], [15, 0, 15, 16, 16, 16],
    [0, 0, 0, 1, 1, 16], [0, 15, 0, 1, 16, 16], [15, 0, 0, 16, 1, 16], [15, 15, 0, 16, 16, 16],
]

# Which side of the room the relay is driving, and how the model that draws the driven face is turned for it.
MARKERS = [
    ("north", "recursive_factory_on", None),
    ("east", "recursive_factory_on", 90),
    ("south", "recursive_factory_on", 180),
    ("west", "recursive_factory_on", 270),
    ("up", "recursive_factory_on_up", None),
    ("down", "recursive_factory_on_down", None),
]


def sides_of(box):
    """The two sides of the room whose planes this bar lies in, in the order the faces are numbered in."""
    spans = dict(zip(AXES, [(box[0], box[3]), (box[1], box[4]), (box[2], box[5])]))
    long_axes = [axis for axis in AXES if spans[axis] == (0, 16)]
    if len(long_axes) != 1:
        raise ValueError("a bar runs the whole way along exactly one axis: " + str(box))
    sides = []
    for axis in AXES:
        if axis == long_axes[0]:
            continue
        if spans[axis] == (0, 1):
            sides.append(LOW[axis])
        elif spans[axis] == (15, 16):
            sides.append(HIGH[axis])
        else:
            raise ValueError("a bar is one sixteenth across: " + str(box))
    return sorted(sides, key=DIRS.index)


def condition(box):
    """What the blockstate asks about before it draws this bar.

    Every side of the room this bar lies in that is joined to the entrance block beside it drops the bar,
    so the bar is drawn when all of the sides it has to ask about are open. The sides above and below are
    not asked about: an entrance block has no neighbour up or down, so the bars along the top and the
    bottom edge of a side answer to that side alone.
    """
    sides = [side for side in sides_of(box) if side in ("north", "south", "west", "east")]
    if not sides:
        raise ValueError("a bar lies in at least one side of the room: " + str(box))
    return {side: "false" for side in sides}


def bar_name(box):
    return "frame_edge_" + "_".join(sides_of(box))


def bar_model(box):
    """One bar, every face of it asking about the direction it points in."""
    faces = {far: {"texture": "#0", "tintindex": FACE_TINT_BASE + index}
             for index, far in enumerate(DIRS)}
    return {
        "parent": NAMESPACE + ":block/frame_part",
        "elements": [{"from": list(box[:3]), "to": list(box[3:]), "faces": faces}],
    }


def marker_ring(out):
    """The bars of the driven face, glowing: they sit on the outer surface of that face's frame, so the
    driven side draws an outline around the opening and covers nothing of the room behind it."""
    if out == "north":
        boxes = [[0, 15, -0.25, 16, 16, 0], [0, 0, -0.25, 16, 1, 0],
                 [0, 1, -0.25, 1, 15, 0], [15, 1, -0.25, 16, 15, 0]]
    elif out == "up":
        boxes = [[0, 16, 0, 16, 16.25, 1], [0, 16, 15, 16, 16.25, 16],
                 [0, 16, 1, 1, 16.25, 15], [15, 16, 1, 16, 16.25, 15]]
    elif out == "down":
        boxes = [[0, -0.25, 0, 16, 0, 1], [0, -0.25, 15, 16, 0, 16],
                 [0, -0.25, 1, 1, 0, 15], [15, -0.25, 1, 16, 0, 15]]
    else:
        raise ValueError(out)
    return [{"from": box[:3], "to": box[3:], "faces": {out: {"texture": "#marker"}}} for box in boxes]


def marker_model(out):
    return {
        "textures": {"marker": MARKER_TEXTURE, "particle": MARKER_TEXTURE},
        "elements": marker_ring(out),
    }


def blockstate():
    """Every bar whose two sides are not joined to the block beside them, then the marker of the driven
    face. The color is not asked about: it tints the frame rather than choosing what of it is drawn."""
    parts = []
    for box in FRAME:
        parts.append({
            "when": condition(box),
            "apply": {"model": NAMESPACE + ":block/" + bar_name(box)},
        })
    for facing, model, rotation in MARKERS:
        apply = {"model": NAMESPACE + ":block/" + model}
        if rotation is not None:
            apply["y"] = rotation
        parts.append({"when": {"powered": "true", "facing": facing}, "apply": apply})
    return {"multipart": parts}


def write(path, model):
    text = json.dumps(model, indent=2) + "\n"
    json.loads(text)
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)
    print(path, len(text), "bytes")


os.makedirs(MODELS, exist_ok=True)
write(os.path.join(MODELS, "frame_part.json"),
      {"textures": {"0": FRAME_TEXTURE, "particle": FRAME_TEXTURE}})
for box in FRAME:
    write(os.path.join(MODELS, bar_name(box) + ".json"), bar_model(box))
# The whole frame, which is what the item is drawn as: the bars are the same ones the blockstate asks for
# one at a time, so an entrance block with nothing joined to it is drawn exactly the way the item is.
write(os.path.join(MODELS, "recursive_factory.json"),
      {"parent": NAMESPACE + ":block/frame_part",
       "elements": [element for box in FRAME for element in bar_model(box)["elements"]]})
# One model per marker to draw, and the face each of them is built for: east, south and west are the same
# north model turned, see MARKERS.
for model, facing in [("recursive_factory_on", "north"),
                      ("recursive_factory_on_up", "up"),
                      ("recursive_factory_on_down", "down")]:
    write(os.path.join(MODELS, model + ".json"), marker_model(facing))
write(os.path.join(BLOCKSTATES, "recursive_factory.json"), blockstate())
