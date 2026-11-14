package cn.splitplay.client;

import cn.splitplay.SplitPlay;
import cn.splitplay.SplitPlayClient;
import cn.splitplay.config.SplitConfig;
import cn.splitplay.launch.SecondInstance;
import cn.splitplay.rawin.RawInputDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lets the player pick which keyboard and which mouse belong to this window.
 *
 * <p>Two windows run the same jar and cannot tell each other apart, so the assignment has to be
 * stored per window: this screen writes the raw-input paths into {@code config/splitplay.json}, and
 * every window keeps its own file. Devices are listed by product name, but the path is what gets
 * saved, because names repeat when both keyboards are the same model.</p>
 */
public class DeviceScreen extends Screen {

    private final Screen parent;
    private final List<Button> keyboardButtons = new ArrayList<>();
    private final List<Button> mouseButtons = new ArrayList<>();

    private List<RawInputDevice> keyboards = List.of();
    private List<RawInputDevice> mice = List.of();
    private String keyboard = "";
    private String mouse = "";
    /** Raw-input path -> role, for devices another running window already took. */
    private Map<String, String> taken = Map.of();

    public DeviceScreen(Screen parent) {
        super(Component.literal("Splitplay devices"));
        this.parent = parent;
        SplitConfig config = SplitPlayClient.config();
        if (config != null) {
            this.keyboard = config.keyboard == null ? "" : config.keyboard;
            this.mouse = config.mouse == null ? "" : config.mouse;
        }
    }

    @Override
    protected void init() {
        keyboardButtons.clear();
        mouseButtons.clear();
        taken = DeviceClaims.claimedElsewhere(SplitPlayClient.gameDir());
        SplitPlay.LOGGER.info("splitplay: device screen for game dir {}, {} device(s) already taken", SplitPlayClient.gameDir(), taken.size());

        List<RawInputDevice> devices = RawInputDevice.enumerate();
        // Windows exposes a software keyboard endpoint that reports nothing useful; keep it last.
        keyboards = devices.stream()
                .filter(RawInputDevice::isKeyboard)
                .sorted(java.util.Comparator.comparingInt(d -> isVirtual(d) ? 1 : 0))
                .toList();
        mice = devices.stream().filter(RawInputDevice::isMouse).toList();

        DeviceScreenLayout layout = layout();
        int left = layout.keyboardX();
        int right = layout.mouseX();
        int width = layout.columnWidth();

        for (int i = 0; i < keyboards.size() && i < layout.visibleRows(); i++) {
            RawInputDevice device = keyboards.get(i);
            Button button = Button.builder(label(device, keyboard, taken), b -> selectKeyboard(device))
                    .bounds(left, layout.rowY(i), width, DeviceScreenLayout.ROW_BUTTON_HEIGHT)
                    .build();
            button.active = !taken.containsKey(normalised(device.path));
            keyboardButtons.add(button);
            addRenderableWidget(button);
        }

        for (int i = 0; i < mice.size() && i < layout.visibleRows(); i++) {
            RawInputDevice device = mice.get(i);
            Button button = Button.builder(label(device, mouse, taken), b -> selectMouse(device))
                    .bounds(right, layout.rowY(i), width, DeviceScreenLayout.ROW_BUTTON_HEIGHT)
                    .build();
            button.active = !taken.containsKey(normalised(device.path));
            mouseButtons.add(button);
            addRenderableWidget(button);
        }

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(layout.saveX(), layout.buttonY(), 150, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(layout.cancelX(), layout.buttonY(), 150, 20)
                .build());
    }

    private void selectKeyboard(RawInputDevice device) {
        if (isTaken(device)) {
            return;
        }
        keyboard = sameAs(keyboard, device) ? "" : device.path;
        refreshLabels(keyboardButtons, keyboards, keyboard);
    }

    private void selectMouse(RawInputDevice device) {
        if (isTaken(device)) {
            return;
        }
        mouse = sameAs(mouse, device) ? "" : device.path;
        refreshLabels(mouseButtons, mice, mouse);
    }

    /** True when the saved path already refers to this device, even if case or prefix differs. */
    private static boolean sameAs(String saved, RawInputDevice device) {
        return saved != null && !saved.isEmpty() && cn.splitplay.util.DevicePath.matches(saved, device.path);
    }

    private boolean isTaken(RawInputDevice device) {
        return taken.containsKey(normalised(device.path));
    }

    private void refreshLabels(List<Button> buttons, List<RawInputDevice> devices, String selected) {
        for (int i = 0; i < buttons.size() && i < devices.size(); i++) {
            buttons.get(i).setMessage(label(devices.get(i), selected, taken));
        }
    }

    private static Component label(RawInputDevice device, String selected, Map<String, String> taken) {
        boolean picked = sameAs(selected, device);
        String owner = taken.get(normalised(device.path));
        // A plain space would be clipped, and "[ ]" reads as "[x]" at some GUI scales.
        String suffix;
        if (owner != null) {
            suffix = " - in use by " + owner;
        } else if (isVirtual(device)) {
            suffix = " (virtual)";
        } else {
            suffix = "";
        }
        return Component.literal((picked ? "> " : "   ") + device.shortLabel() + suffix);
    }

    /** Rows and columns for the current window size; the arithmetic lives in a testable class. */
    private DeviceScreenLayout layout() {
        return DeviceScreenLayout.of(this.width, this.height);
    }

    private static String normalised(String path) {
        return DeviceClaims.normalise(path);
    }


    /** True for the software keyboard endpoint Windows always exposes. */
    private static boolean isVirtual(RawInputDevice device) {
        return device.path != null && device.path.toLowerCase(java.util.Locale.ROOT).contains("keyboard rid");
    }

    private void save() {
        SplitConfig config = SplitPlayClient.config();
        if (config == null) {
            onClose();
            return;
        }
        config.keyboard = keyboard;
        config.mouse = mouse;
        if (config.role == null || config.role.isEmpty()) {
            config.role = SecondInstance.isChild() ? "P2" : "P1";
        }
        // Both devices assigned means this window can take over its own input.
        config.enabled = config.isComplete();
        config.save(SplitPlayClient.configFile());
        SplitPlayClient.reload();
        SplitPlay.LOGGER.info("splitplay: devices saved, keyboard={} mouse={} active={}",
                keyboard.isEmpty() ? "<none>" : keyboard,
                mouse.isEmpty() ? "<none>" : mouse,
                config.isolatesInput());
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        SplitConfig config = SplitPlayClient.config();
        String role = config == null || config.role == null || config.role.isEmpty()
                ? "unnamed" : config.role;
        graphics.drawCenteredString(this.font,
                Component.literal("This window is " + role
                        + ". Pick one keyboard and one mouse; the other window picks the rest."),
                this.width / 2, 34, 0xA0A0A0);

        DeviceScreenLayout layout = layout();
        int left = layout.keyboardX();
        int right = layout.mouseX();
        graphics.drawString(this.font, "Keyboard", left, 44, 0xFFFF66);
        graphics.drawString(this.font, "Mouse", right, 44, 0xFFFF66);
        if (keyboards.isEmpty()) {
            graphics.drawString(this.font, "no keyboard found", left, DeviceScreenLayout.ROW_TOP, 0xFF6666);
        }
        if (mice.isEmpty()) {
            graphics.drawString(this.font, "no mouse found", right, DeviceScreenLayout.ROW_TOP, 0xFF6666);
        }
        // A row that does not fit has no button at all; saying so beats a device that silently does not
        // exist in this window, because the only way out would be to guess.
        int hiddenKeyboards = layout.hidden(keyboards.size());
        int hiddenMice = layout.hidden(mice.size());
        if (hiddenKeyboards > 0 || hiddenMice > 0) {
            graphics.drawCenteredString(this.font,
                    Component.literal("window too short: " + hiddenKeyboards + " keyboard(s) and "
                            + hiddenMice + " mouse/mice hidden"),
                    this.width / 2, layout.rowsBottom() - 8, 0xFF6666);
        }
        String state = config != null && config.isolatesInput()
                ? "Input is split: this window only listens to the devices above."
                : "Input is not split yet: assign both devices and save.";
        graphics.drawCenteredString(this.font, Component.literal(state),
                this.width / 2, this.height - 58, 0x80FF80);
        if (!taken.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("Devices marked \"in use\" belong to the other window and cannot be picked."),
                    this.width / 2, this.height - 70, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft == null) {
            return;
        }
        // Returning to the parent keeps the caller in charge; a null parent means "whatever was
        // behind", which in a world is the world and not the main menu.
        this.minecraft.setScreen(parent);
    }
}
