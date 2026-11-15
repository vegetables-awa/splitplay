package cn.splitplay.client;

import cn.splitplay.SplitPlay;
import cn.splitplay.SplitPlayClient;
import cn.splitplay.config.SplitConfig;
import cn.splitplay.launch.SecondInstance;
import cn.splitplay.rawin.RawInputDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lets the player pick which keyboard and which mouse belong to this window, and set the two
 * sensitivity multipliers.
 *
 * <p>Two windows run the same jar and cannot tell each other apart, so the assignment has to be
 * stored per window: this screen writes the raw-input paths into {@code config/splitplay.json}, and
 * every window keeps its own file. Devices are listed by product name, but the path is what gets
 * saved, because names repeat when both keyboards are the same model.</p>
 *
 * <p>All visible text is translated ({@code assets/splitplay/lang}), so the screen reads Chinese in a
 * Chinese game and English everywhere else.</p>
 */
public class DeviceScreen extends Screen {

    private static final double LOOK_MIN = 0.10D;
    private static final double LOOK_MAX = 3.00D;
    private static final double CURSOR_MIN = 0.25D;
    private static final double CURSOR_MAX = 3.00D;
    /** Room for "Sound: Speakers (USB Audio)" at GUI scale 2. */
    private static final int SOUND_BUTTON_WIDTH = 200;

    private final Screen parent;
    private final List<Button> keyboardButtons = new ArrayList<>();
    private final List<Button> mouseButtons = new ArrayList<>();

    private List<RawInputDevice> keyboards = List.of();
    private List<RawInputDevice> mice = List.of();
    private String keyboard = "";
    private String mouse = "";
    private double lookSensitivity = 1.0D;
    private double cursorSensitivity = 1.0D;
    private String soundDevice = "";
    private List<String> soundOptions = List.of();
    private Button soundButton;
    private SensitivitySlider lookSlider;
    private SensitivitySlider cursorSlider;
    /** Raw-input path -> role, for devices another running window already took. */
    private Map<String, String> taken = Map.of();

    public DeviceScreen(Screen parent) {
        super(Component.translatable("splitplay.devices.title"));
        this.parent = parent;
        SplitConfig config = SplitPlayClient.config();
        if (config != null) {
            this.keyboard = config.keyboard == null ? "" : config.keyboard;
            this.mouse = config.mouse == null ? "" : config.mouse;
            this.lookSensitivity = config.sensitivity;
            this.cursorSensitivity = config.cursorSensitivity;
            this.soundDevice = config.soundDevice == null ? "" : config.soundDevice;
        }
    }

    @Override
    protected void init() {
        keyboardButtons.clear();
        mouseButtons.clear();
        taken = DeviceClaims.claimedElsewhere(SplitPlayClient.gameDir());
        SplitPlay.LOGGER.info("splitplay: device screen for game dir {}, {} device(s) already taken",
                SplitPlayClient.gameDir(), taken.size());

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
        int columnWidth = layout.columnWidth();

        for (int i = 0; i < keyboards.size() && i < layout.visibleRows(); i++) {
            RawInputDevice device = keyboards.get(i);
            Button button = Button.builder(label(device, keyboard, taken), b -> selectKeyboard(device))
                    .bounds(left, layout.rowY(i), columnWidth, DeviceScreenLayout.ROW_BUTTON_HEIGHT)
                    .build();
            button.active = !taken.containsKey(normalised(device.path));
            keyboardButtons.add(button);
            addRenderableWidget(button);
        }

        for (int i = 0; i < mice.size() && i < layout.visibleRows(); i++) {
            RawInputDevice device = mice.get(i);
            Button button = Button.builder(label(device, mouse, taken), b -> selectMouse(device))
                    .bounds(right, layout.rowY(i), columnWidth, DeviceScreenLayout.ROW_BUTTON_HEIGHT)
                    .build();
            button.active = !taken.containsKey(normalised(device.path));
            mouseButtons.add(button);
            addRenderableWidget(button);
        }

        lookSlider = new SensitivitySlider(left, layout.sliderY(), columnWidth, 20, lookSensitivity, false);
        cursorSlider = new SensitivitySlider(right, layout.sliderY(), columnWidth, 20, cursorSensitivity, true);
        addRenderableWidget(lookSlider);
        addRenderableWidget(cursorSlider);

        addRenderableWidget(Button.builder(Component.translatable("splitplay.devices.save"), b -> save())
                .bounds(layout.saveX(), layout.buttonY(), 150, 20)
                .build());
        soundOptions = SoundDevices.available();
        soundButton = Button.builder(soundLabel(), b -> cycleSound())
                .bounds(this.width - SOUND_BUTTON_WIDTH - 6, 6, SOUND_BUTTON_WIDTH, 20)
                .build();
        addRenderableWidget(soundButton);

        addRenderableWidget(Button.builder(Component.translatable("splitplay.devices.cancel"), b -> onClose())
                .bounds(layout.cancelX(), layout.buttonY(), 150, 20)
                .build());
    }

    /** "Default", then every device OpenAL reports, with the current choice first. */
    private List<String> soundChoices() {
        List<String> choices = new ArrayList<>();
        choices.add("");
        for (String name : soundOptions) {
            if (!choices.contains(name)) {
                choices.add(name);
            }
        }
        if (!soundDevice.isEmpty() && !choices.contains(soundDevice)) {
            choices.add(soundDevice);
        }
        return choices;
    }

    private void cycleSound() {
        List<String> choices = soundChoices();
        int index = choices.indexOf(soundDevice);
        soundDevice = choices.get((index + 1) % choices.size());
        if (soundButton != null) {
            soundButton.setMessage(soundLabel());
        }
    }

    private Component soundLabel() {
        Component name = soundDevice.isEmpty()
                ? Component.translatable("splitplay.sound.default")
                : Component.literal(soundDevice);
        return Component.translatable("splitplay.sound.label", name);
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
        Component suffix = Component.empty();
        if (owner != null) {
            suffix = Component.translatable("splitplay.devices.in_use", owner);
        } else if (isVirtual(device)) {
            suffix = Component.translatable("splitplay.devices.virtual");
        }
        return Component.literal(picked ? "> " : "   ").append(device.shortLabel()).append(suffix);
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
        return device.path != null && device.path.toLowerCase(Locale.ROOT).contains("keyboard rid");
    }

    private void save() {
        SplitConfig config = SplitPlayClient.config();
        if (config == null) {
            onClose();
            return;
        }
        config.keyboard = keyboard;
        config.mouse = mouse;
        if (lookSlider != null) {
            config.sensitivity = lookSlider.value();
        }
        if (cursorSlider != null) {
            config.cursorSensitivity = cursorSlider.value();
        }
        config.soundDevice = soundDevice;
        if (config.role == null || config.role.isEmpty()) {
            config.role = SecondInstance.isChild() ? "P2" : "P1";
        }
        // Both devices assigned means this window can take over its own input.
        config.enabled = config.isComplete();
        config.save(SplitPlayClient.configFile());
        SplitPlayClient.reload();
        SplitPlay.LOGGER.info("splitplay: devices saved, keyboard={} mouse={} look={} cursor={} sound={} active={}",
                keyboard.isEmpty() ? "<none>" : keyboard,
                mouse.isEmpty() ? "<none>" : mouse,
                String.format(Locale.ROOT, "%.2f", config.sensitivity),
                String.format(Locale.ROOT, "%.2f", config.cursorSensitivity),
                soundDevice.isEmpty() ? "<system default>" : soundDevice,
                config.isolatesInput());
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        SplitConfig config = SplitPlayClient.config();
        String role = config == null || config.role == null || config.role.isEmpty()
                ? Component.translatable("splitplay.devices.unnamed").getString() : config.role;
        graphics.drawCenteredString(this.font,
                Component.translatable("splitplay.devices.header", role),
                this.width / 2, 34, 0xA0A0A0);

        DeviceScreenLayout layout = layout();
        int left = layout.keyboardX();
        int right = layout.mouseX();
        graphics.drawString(this.font, Component.translatable("splitplay.devices.keyboard"), left, 44, 0xFFFF66);
        graphics.drawString(this.font, Component.translatable("splitplay.devices.mouse"), right, 44, 0xFFFF66);
        if (keyboards.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("splitplay.devices.none.keyboard"),
                    left, DeviceScreenLayout.ROW_TOP, 0xFF6666);
        }
        if (mice.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("splitplay.devices.none.mouse"),
                    right, DeviceScreenLayout.ROW_TOP, 0xFF6666);
        }
        // A row that does not fit has no button at all; saying so beats a device that silently does not
        // exist in this window, because the only way out would be to guess.
        int hiddenKeyboards = layout.hidden(keyboards.size());
        int hiddenMice = layout.hidden(mice.size());
        if (hiddenKeyboards > 0 || hiddenMice > 0) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("splitplay.devices.hidden", hiddenKeyboards, hiddenMice),
                    this.width / 2, layout.rowsBottom() - 8, 0xFF6666);
        }
        Component state = config != null && config.isolatesInput()
                ? Component.translatable("splitplay.devices.split")
                : Component.translatable("splitplay.devices.not_split");
        graphics.drawCenteredString(this.font, state, this.width / 2, this.height - 58, 0x80FF80);
        if (!taken.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("splitplay.devices.taken_hint"),
                    this.width / 2, layout.rowsBottom() - 20, 0xA0A0A0);
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

    /** One multiplier, shown as "label: 1.00" and never as a raw 0..1 slider position. */
    private static final class SensitivitySlider extends AbstractSliderButton {

        private final boolean cursor;

        SensitivitySlider(int x, int y, int width, int height, double initial, boolean cursor) {
            super(x, y, width, height, Component.empty(), progressOf(initial, cursor));
            this.cursor = cursor;
            updateMessage();
        }

        private static double min(boolean cursor) {
            return cursor ? CURSOR_MIN : LOOK_MIN;
        }

        private static double max(boolean cursor) {
            return cursor ? CURSOR_MAX : LOOK_MAX;
        }

        private static double progressOf(double value, boolean cursor) {
            double min = min(cursor);
            double span = max(cursor) - min;
            if (span <= 0.0D) {
                return 0.0D;
            }
            return Math.max(0.0D, Math.min(1.0D, (value - min) / span));
        }

        /** The multiplier this slider stands for, rounded to two decimals. */
        double value() {
            double raw = min(cursor) + this.value * (max(cursor) - min(cursor));
            return Math.round(raw * 100.0D) / 100.0D;
        }

        @Override
        protected void updateMessage() {
            String key = cursor ? "splitplay.sensitivity.cursor" : "splitplay.sensitivity.look";
            setMessage(Component.translatable(key, String.format(Locale.ROOT, "%.2f", value())));
        }

        @Override
        protected void applyValue() {
            // Applied on Save: a slider that writes the config on every drag would spin the disk while
            // the player is still deciding.
        }
    }
}
