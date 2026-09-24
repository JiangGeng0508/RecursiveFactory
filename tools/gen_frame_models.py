"""Generates the entrance block's frame models and its blockstate.

The block is a frame: twelve one sixteenth bars along the edges of the block with the middle open, so the
preview of the room shows through it (see RecursiveFactoryRenderer). A face of a bar is tinted when it lies
on the outer surface of the block, in the color of the side of the room whose plane that surface is, which
is FactoryColors.FACE_TINT_BASE + the data value of the direction the face points in. The faces that look
into the hollow of the frame carry tint index 0, the factory's own color instead: the two faces across a one
sixteenth bar are the two sides of the same sliver, and tinting the inward one by whichever direction it
happens to point in repaints the far side of the frame along with the near one.

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

# Which axis each face is across, and whether it is the low or the high end of that axis.
FACES = {
    "down": ("y", True), "up": ("y", False),
    "north": ("z", True), "south": ("z", False),
    "west": ("x", True), "east": ("x", False),
}
# Where along each axis the low end of it starts, and how far apart the two ends of a face are.
SPAN = {"x": (0, 3), "y": (1, 4), "z": (2, 5)}
# The tint index of each side of the room, in the order the face modes are numbered in.
TINT = {side: FACE_TINT_BASE + index for index, side in enumerate(DIRS)}


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


def outer_face(box, face):
    """Whether this face of the bar lies on the outer surface of the block.

    A bar sits on an edge of the block, so the faces of it that look out of the block sit on the block's own
    surface, at 0 or 16 along their axis; the other ones look into the hollow of the frame, and belong to no
    side of the room at all.
    """
    axis, low = FACES[face]
    first, last = SPAN[axis]
    return box[first if low else last] == (0 if low else 16)


def bar_model(box):
    """One bar, each face of it asking about the side of the room it lies in, or about no side at all."""
    faces = {face: {"texture": "#0", "tintindex": TINT[face] if outer_face(box, face) else 0}
             for face in DIRS}
    return {
        "parent": NAMESPACE + ":block/frame_part",
        "elements": [{"from": list(box[:3]), "to": list(box[3:]), "faces": faces}],
    }


def blockstate():
    """Every bar whose two sides are not joined to the block beside them. Neither the color nor whether the
    relay is driving this block is asked about: the color tints the frame rather than choosing what of it is
    drawn, and what is drawn does not change when a relay lights up."""
    parts = []
    for box in FRAME:
        parts.append({
            "when": condition(box),
            "apply": {"model": NAMESPACE + ":block/" + bar_name(box)},
        })
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
write(os.path.join(BLOCKSTATES, "recursive_factory.json"), blockstate())
