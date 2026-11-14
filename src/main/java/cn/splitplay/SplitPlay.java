package cn.splitplay;

import cn.splitplay.launch.SplitLauncher;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;

/**
 * Entry point of splitplay.
 *
 * <p>splitplay lets two people play Minecraft side by side on one computer: two game windows, one
 * per player, each driven by its own keyboard and mouse. This class only holds the mod identity and
 * hands over to {@link SplitPlayClient}.
 */
public final class SplitPlay implements ClientModInitializer {

    public static final String MOD_ID = "splitplay";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitializeClient() {
        LOGGER.info("splitplay loading");
        SplitPlayClient.init();
        Runtime.getRuntime().addShutdownHook(new Thread(SplitLauncher::onShutdown, "splitplay-shutdown"));
    }
}
