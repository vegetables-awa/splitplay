package cn.splitplay.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads where a container's panel is drawn, so the cursor can tell when it is over a slot. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {

    @Accessor("leftPos")
    int splitplay$leftPos();

    @Accessor("topPos")
    int splitplay$topPos();
}
