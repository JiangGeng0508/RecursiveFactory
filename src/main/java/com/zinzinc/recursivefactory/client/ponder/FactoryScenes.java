package com.zinzinc.recursivefactory.client.ponder;

import com.simibubi.create.AllItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;

/** Original explanatory scenes; they operate only on Ponder's demonstration world. */
public final class FactoryScenes {
    private static void base(SceneBuilder scene, SceneBuildingUtil util, String name, String title) {
        scene.title(name, title);
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(0.75f);
        scene.showBasePlate();
        scene.idle(10);
    }

    public static void connections(SceneBuilder scene, SceneBuildingUtil util) {
        base(scene, util, "connections", "Connecting the inside and outside");
        scene.world().showSection(util.select().fromTo(1, 1, 1, 3, 2, 6), Direction.DOWN);
        scene.overlay().showText(90).text("Each entrance face connects to the wall facing the same direction inside its room.")
                .pointAt(util.vector().centerOf(2, 1, 4)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.world().showSection(util.select().fromTo(5, 1, 2, 7, 2, 6), Direction.DOWN);
        scene.overlay().showText(90).text("Input from the north reaches the north wall. Place the receiving container against that wall.")
                .pointAt(util.vector().centerOf(6, 1, 4)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.overlay().showControls(util.vector().centerOf(2, 1, 4), Pointing.DOWN, 50).rightClick().withItem(AllItems.WRENCH.asStack());
        scene.overlay().showControls(util.vector().centerOf(6, 1, 3), Pointing.DOWN, 50).rightClick().withItem(AllItems.WRENCH.asStack());
        scene.overlay().showText(90).text("Enable redstone at both ends with a normal wrench right-click, or sneak-right-click with empty hands.")
                .pointAt(util.vector().centerOf(6, 1, 3)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.overlay().showText(90).text("Engineer goggles show the selected face, server transfer settings and waiting buffers.")
                .pointAt(util.vector().centerOf(2, 1, 4)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
    }

    public static void expansion(SceneBuilder scene, SceneBuildingUtil util) {
        base(scene, util, "expansion", "Expanding and nesting factories");
        scene.world().showSection(util.select().position(2, 1, 2), Direction.DOWN);
        scene.overlay().showText(90).text("One entrance represents a 16 by 16 by 16 room cell. Enter with an empty-hand right-click.")
                .pointAt(util.vector().centerOf(2, 1, 2)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.world().showSection(util.select().position(3, 1, 2), Direction.WEST);
        scene.world().showSection(util.select().position(2, 2, 2), Direction.DOWN);
        scene.overlay().showText(90).text("Place entrances beside, above or below an existing entrance to expand the same factory in that shape.")
                .pointAt(util.vector().centerOf(2, 2, 2)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.world().showSection(util.select().fromTo(5, 1, 4, 7, 2, 7), Direction.DOWN);
        scene.overlay().showText(90).text("An entrance placed inside a room creates a nested factory. Copies receive independent rooms.")
                .pointAt(util.vector().centerOf(6, 1, 6)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.overlay().showText(90).text("Right-click a barrier to return one level. Each entrance still transfers through its own room cell.")
                .pointAt(util.vector().centerOf(6, 1, 5)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
    }

    public static void printing(SceneBuilder scene, SceneBuildingUtil util) {
        base(scene, util, "printing", "Copying with the Schematicannon");
        scene.world().showSection(util.select().fromTo(1, 1, 2, 3, 2, 5), Direction.DOWN);
        scene.overlay().showControls(util.vector().centerOf(6, 1, 4), Pointing.DOWN, 60).withItem(AllItems.SCHEMATIC_AND_QUILL.asStack());
        scene.overlay().showText(90).text("Use Schematic and Quill to select an entrance, save the schematic, then deploy it for the Schematicannon.")
                .pointAt(util.vector().centerOf(2, 1, 4)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.overlay().showControls(util.vector().centerOf(2, 1, 4), Pointing.DOWN, 60).withItem(Items.GUNPOWDER.getDefaultInstance());
        scene.overlay().showText(90).text("Supply gunpowder and construction materials in adjacent containers. Missing materials pause construction.")
                .pointAt(util.vector().centerOf(2, 1, 3)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.world().showSection(util.select().position(6, 1, 4), Direction.DOWN);
        scene.overlay().showText(90).text("The entrance is placed first. The cannon then builds the captured room contents one block at a time.")
                .pointAt(util.vector().centerOf(6, 1, 4)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
        scene.world().showSection(util.select().fromTo(5, 1, 5, 7, 2, 7), Direction.DOWN);
        scene.overlay().showText(90).text("Refill missing materials to continue. Progress survives saving; expanded and nested rooms are copied independently.")
                .pointAt(util.vector().centerOf(6, 1, 6)).attachKeyFrame().placeNearTarget();
        scene.idle(100);
    }
}
