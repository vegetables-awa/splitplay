package cn.splitplay.mixin;

import cn.splitplay.SplitPlayClient;
import com.mojang.blaze3d.audio.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Opens a different OpenAL output device when this window was given one.
 *
 * <p>{@code SoundEngine} calls {@code Library.init(name, directionalAudio)} with the value of the
 * vanilla "sound device" option, and that option lives in {@code options.txt} - one file per game
 * directory, which both windows share. Swapping the argument here is what lets player one hear the
 * speakers while player two hears a headset, without splitting the install.</p>
 */
@Mixin(Library.class)
public class LibraryMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("splitplay");

    @ModifyVariable(method = "init", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String splitplay$useConfiguredSoundDevice(String device) {
        String wanted = SplitPlayClient.configuredSoundDevice();
        if (wanted == null || wanted.isEmpty()) {
            return device;
        }
        if (!wanted.equals(device)) {
            LOGGER.info("splitplay: opening sound device '{}' instead of '{}'", wanted, device);
        }
        return wanted;
    }
}
