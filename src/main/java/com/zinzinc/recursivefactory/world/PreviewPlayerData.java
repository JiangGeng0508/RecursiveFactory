package com.zinzinc.recursivefactory.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;

/** Only the public appearance of a player travels in a preview, never their saved inventory or data. */
public final class PreviewPlayerData {
    public static final String PROFILE = "PreviewPlayer";
    public static final String MODEL_PARTS = "ModelParts";

    private PreviewPlayerData() {}

    public static CompoundTag sample(Player player) {
        CompoundTag tag = new CompoundTag();
        CompoundTag profile = new CompoundTag();
        profile.putUUID("Id", player.getUUID());
        profile.putString("Name", player.getGameProfile().getName());
        tag.put(PROFILE, profile);
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(player.getYRot()));
        rotation.add(FloatTag.valueOf(player.getXRot()));
        tag.put("Rotation", rotation);
        tag.putFloat("BodyYaw", player.yBodyRot);
        tag.putFloat("HeadYaw", player.yHeadRot);
        tag.putString("Pose", player.getPose().name());
        tag.putBoolean("Crouching", player.isShiftKeyDown());
        tag.putBoolean("Invisible", player.isInvisible());
        tag.putBoolean("LeftHanded", player.getMainArm() == HumanoidArm.LEFT);
        tag.putFloat("Attack", player.attackAnim);
        int parts = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) if (player.isModelPartShown(part)) parts |= part.getMask();
        tag.putByte(MODEL_PARTS, (byte) parts);
        CompoundTag equipment = new CompoundTag();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.BODY) continue;
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) equipment.put(slot.getName(), stack.save(player.registryAccess()));
        }
        tag.put("Equipment", equipment);
        return tag;
    }

    /** Applied only to the preview proxy. Does not call Player.load or touch any live player. */
    public static void apply(Player player, CompoundTag tag) {
        ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
        ListTag rotation = tag.getList("Rotation", Tag.TAG_FLOAT);
        player.setPos(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
        player.setYRot(rotation.getFloat(0));
        player.setXRot(rotation.getFloat(1));
        player.yBodyRot = tag.getFloat("BodyYaw");
        player.yHeadRot = tag.getFloat("HeadYaw");
        player.setPose(Pose.valueOf(tag.getString("Pose")));
        player.setShiftKeyDown(tag.getBoolean("Crouching"));
        player.setInvisible(tag.getBoolean("Invisible"));
        player.setMainArm(tag.getBoolean("LeftHanded") ? HumanoidArm.LEFT : HumanoidArm.RIGHT);
        player.oAttackAnim = player.attackAnim;
        player.attackAnim = tag.getFloat("Attack");
        CompoundTag equipment = tag.getCompound("Equipment");
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.BODY) continue;
            player.setItemSlot(slot, ItemStack.parseOptional(player.registryAccess(), equipment.getCompound(slot.getName())));
        }
    }
}
