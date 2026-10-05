package com.zinzinc.recursivefactory.client.render;

import com.mojang.authlib.GameProfile;
import com.zinzinc.recursivefactory.world.PreviewPlayerData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.nbt.CompoundTag;

/** A render-only player, never registered in the client world's entity list or network tracking. */
public final class PreviewPlayer extends RemotePlayer {
    public PreviewPlayer(ClientLevel level, CompoundTag tag) {
        super(level, profile(tag));
        load(tag);
        yBodyRotO = yBodyRot;
        yHeadRotO = yHeadRot;
        oAttackAnim = attackAnim;
    }

    private static GameProfile profile(CompoundTag tag) {
        CompoundTag profile = tag.getCompound(PreviewPlayerData.PROFILE);
        return new GameProfile(profile.getUUID("Id"), profile.getString("Name"));
    }

    @Override
    public void load(CompoundTag tag) {
        PreviewPlayerData.apply(this, tag);
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, tag.getByte(PreviewPlayerData.MODEL_PARTS));
    }
}
