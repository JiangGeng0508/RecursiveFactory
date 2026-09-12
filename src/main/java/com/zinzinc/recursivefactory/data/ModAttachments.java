package com.zinzinc.recursivefactory.data;

import com.zinzinc.recursivefactory.RecursiveFactory;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, RecursiveFactory.MODID);

    public static final Supplier<AttachmentType<ReturnStackData>> RETURN_STACK = ATTACHMENT_TYPES.register(
            "return_stack",
            () -> AttachmentType.builder(ReturnStackData::empty)
                    .serialize(ReturnStackData.CODEC)
                    .copyOnDeath()
                    .build()
    );

    private ModAttachments() {
    }
}
