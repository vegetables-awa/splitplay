package cn.splitplay.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Cursor position plus the vanilla handlers; replayed mouse events are absolute positions. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    /** The client this handler belongs to, needed by the per-tick injection. */
    @Accessor("minecraft")
    Minecraft splitplay$minecraft();

    @Accessor("xpos")
    double splitplay$xpos();

    @Accessor("ypos")
    double splitplay$ypos();

    @Accessor("xpos")
    void splitplay$setXpos(double xpos);

    @Accessor("ypos")
    void splitplay$setYpos(double ypos);

    /**
     * Vanilla only runs continuous attack/use while this flag is true, and a window without OS focus
     * can never grab the real cursor. Setting the flag directly keeps both players able to mine.
     */
    @Accessor("mouseGrabbed")
    void splitplay$setMouseGrabbed(boolean grabbed);

    /** The button vanilla thinks is down; drag detection and the cursor colour both read it. */
    @Accessor("activeButton")
    void splitplay$setActiveButton(int button);

    @Accessor("mousePressedTime")
    void splitplay$setMousePressedTime(double seconds);

    @Invoker("onPress")
    void splitplay$onPress(long window, int button, int action, int modifiers);

    @Invoker("onScroll")
    void splitplay$onScroll(long window, double xOffset, double yOffset);

    @Invoker("onMove")
    void splitplay$onMove(long window, double x, double y);
}
