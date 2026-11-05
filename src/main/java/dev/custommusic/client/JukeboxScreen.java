package dev.custommusic.client;

import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.JukeboxPayloads.JukeboxActionPayload;
import dev.custommusic.common.payload.JukeboxPayloads.JukeboxStatePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The Juke Box menu (opened by right-clicking the block). One screen, three things:
 * a track list (double-click plays), a Stop button + Loop toggle, and the two sliders —
 * volume (gain) and hearing range (blocks). Everything is validated and echoed by the
 * server, so the screen simply renders the latest {@link JukeboxStatePayload}.
 */
public class JukeboxScreen extends Screen {
    // Keep in sync with the server-side clamp (CustomJukeboxBlockEntity).
    private static final float MIN_VOLUME = 0.10f;
    private static final float MAX_VOLUME = 1.00f;
    private static final int MIN_RANGE = 4;
    private static final int MAX_RANGE = 96;
    /** Auto-close when the player walks farther than this from the block. */
    private static final double CLOSE_DISTANCE = 16.0;
    /** Slider settings are debounced; sent at most twice a second while dragging. */
    private static final long SEND_DEBOUNCE_MS = 500;

    private final BlockPos pos;
    private TrackListWidget list;
    private Button stopButton;
    private Button loopButton;
    private VolumeSlider volumeSlider;
    private RangeSlider rangeSlider;
    /** The state payload last applied to the widgets (payload records are identity-compared). */
    private JukeboxStatePayload lastSeen;
    /** Optimistic loop flag so the toggle feels instant; corrected by the server echo. */
    private boolean loop;

    public JukeboxScreen(BlockPos pos) {
        super(Component.translatable("screen.custommusic.jukebox"));
        this.pos = pos;
        JukeboxStatePayload state = JukeboxClientManager.getState(pos);
        if (state != null) {
            this.loop = state.loop();
            this.lastSeen = state;
        }
    }

    @Override
    protected void init() {
        super.init();
        JukeboxStatePayload state = JukeboxClientManager.getState(pos);

        list = addRenderableWidget(new TrackListWidget(minecraft, width, height, ClientTrackCache.available()));
        stopButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.custommusic.jukebox_stop"), b -> sendStop())
            .bounds(width / 2 - 100, height - 64, 98, 20).build());

        loopButton = addRenderableWidget(Button.builder(loopMessage(), b -> toggleLoop())
            .bounds(width / 2 + 2, height - 64, 98, 20).build());

        volumeSlider = addRenderableWidget(new VolumeSlider(width / 2 - 100, height - 108, 200, 20,
            state != null ? state.volume() : 1.0f));
        rangeSlider = addRenderableWidget(new RangeSlider(width / 2 - 100, height - 86, 200, 20,
            state != null ? state.range() : 16));
        if (state != null) {
            lastSeen = state;
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
            .bounds(width / 2 - 100, height - 32, 200, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        volumeSlider.flush(false);
        rangeSlider.flush(false);

        JukeboxStatePayload state = JukeboxClientManager.getState(pos);
        if (state != null && state != lastSeen) {
            lastSeen = state;
            loop = state.loop();
            loopButton.setMessage(loopMessage());
        }

        // Close the menu when walking away — just like vanilla containers.
        LocalPlayer player = minecraft.player;
        if (player != null) {
            double d = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d > CLOSE_DISTANCE * CLOSE_DISTANCE) {
                onClose();
            }
        }
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);

        JukeboxStatePayload state = JukeboxClientManager.getState(pos);
        boolean hasTracks = !ClientTrackCache.available().isEmpty();
        if (state != null && state.playing() && !state.slug().isEmpty()) {
            TrackEntry entry = findBySlug(state.slug());
            String name = entry != null ? entry.title() : state.slug();
            gui.drawCenteredString(font,
                Component.literal("♪ " + name).withStyle(ChatFormatting.GOLD),
                width / 2, 22, 0xFFFFFFFF);
        } else if (hasTracks) {
            gui.drawCenteredString(font,
                Component.translatable("screen.custommusic.jukebox_stopped")
                    .withStyle(ChatFormatting.GRAY),
                width / 2, 22, 0xFFAAAAAA);
        } else {
            gui.drawCenteredString(font,
                Component.translatable("screen.custommusic.empty"),
                width / 2, 22, 0xFFAAAAAA);
        }
        if (hasTracks) {
            gui.drawCenteredString(font,
                Component.translatable("screen.custommusic.jukebox_hint")
                    .withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height - 122, 0xFF555555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- actions

    private void sendStop() {
        PacketDistributor.sendToServer(JukeboxActionPayload.stop(pos));
    }

    private void toggleLoop() {
        loop = !loop;
        loopButton.setMessage(loopMessage());
        volumeSlider.flush(true);
        rangeSlider.flush(true);
        sendSettings();
    }

    private void sendSettings() {
        PacketDistributor.sendToServer(JukeboxActionPayload.settings(pos, loop,
            volumeSlider.currentValue(), Math.round(rangeSlider.currentValue())));
    }

    private void play(TrackEntry track) {
        volumeSlider.flush(true); // apply any half-debounced slider state first
        rangeSlider.flush(true);
        PacketDistributor.sendToServer(JukeboxActionPayload.play(pos, track.slug()));
    }

    private Component loopMessage() {
        return Component.translatable("screen.custommusic.jukebox_loop",
            Component.translatable(loop ? "screen.custommusic.on" : "screen.custommusic.off")
                .withStyle(loop ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static TrackEntry findBySlug(String slug) {
        for (TrackEntry entry : ClientTrackCache.available()) {
            if (entry.slug().equals(slug)) {
                return entry;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- widgets

    /** A slider that reports its value as a float and debounces payload sending. */
    private abstract class DebouncedSlider extends AbstractSliderButton {
        private long lastSend;
        private boolean dirty;
        private final float min;
        private final float max;

        DebouncedSlider(int x, int y, int width, int height, float value, float min, float max) {
            super(x, y, width, height, Component.empty(), (Mth.clamp(value, min, max) - min) / (max - min));
            this.min = min;
            this.max = max;
            updateMessage();
        }

        float currentValue() {
            return (float) (value * (max - min) + min);
        }

        void setDisplayed(float newValue) {
            this.value = (Mth.clamp(newValue, min, max) - min) / (max - min);
            updateMessage();
        }

        /** Marked by {@link #applyValue()}; sent from {@link #flush(boolean)} or on release. */
        void markDirty() {
            dirty = true;
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            flush(true);
        }

        void flush(boolean immediate) {
            if (!dirty) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!immediate && now - lastSend < SEND_DEBOUNCE_MS) {
                return;
            }
            dirty = false;
            lastSend = now;
            sendSettings();
        }
    }

    private class VolumeSlider extends DebouncedSlider {
        VolumeSlider(int x, int y, int width, int height, float volume) {
            super(x, y, width, height, volume, MIN_VOLUME, MAX_VOLUME);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("screen.custommusic.jukebox_volume",
                Math.round(super.currentValue() * 100)));
        }

        @Override
        protected void applyValue() {
            markDirty();
        }
    }

    private class RangeSlider extends DebouncedSlider {
        RangeSlider(int x, int y, int width, int height, int range) {
            super(x, y, width, height, range, MIN_RANGE, MAX_RANGE);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("screen.custommusic.jukebox_range",
                Math.round(super.currentValue())));
        }

        @Override
        protected void applyValue() {
            markDirty();
        }
    }

    /** Clickable track list; single click selects, double-click plays (same gesture as the disc chooser). */
    private class TrackListWidget extends ObjectSelectionList<TrackListWidget.Entry> {
        TrackListWidget(Minecraft mc, int screenWidth, int screenHeight, List<TrackEntry> tracks) {
            // itemHeight=20 leaves a 1-px margin above/below the text line (see ChooseTrackScreen).
            super(mc, screenWidth, screenHeight, 48, 20);
            updateSizeAndPosition(screenWidth, screenHeight - 116 - 48, 48);
            for (TrackEntry track : tracks) {
                addEntry(new Entry(track));
            }
        }

        private class Entry extends ObjectSelectionList.Entry<Entry> {
            final TrackEntry track;
            private long lastClick;

            Entry(TrackEntry track) {
                this.track = track;
            }

            @Override
            public void render(GuiGraphics gui, int index, int top, int left, int width, int height,
                                int mouseX, int mouseY, boolean hovered, float partialTick) {
                var f = JukeboxScreen.this.font;
                JukeboxStatePayload state = JukeboxClientManager.getState(pos);
                boolean isPlaying = state != null && state.playing() && track.slug().equals(state.slug());
                net.minecraft.network.chat.MutableComponent title =
                    Component.literal((isPlaying ? "♪ " : "") + track.title() + "  [" + fmt(track.durationSeconds()) + "]");
                if (isPlaying) {
                    title.withStyle(ChatFormatting.GOLD);
                }
                gui.drawString(f, title, left + width / 2 - f.width(title) / 2, top + 6,
                    0xFFFFFFFF, true);
            }

            @Override
            public Component getNarration() {
                return Component.literal(track.title());
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastClick < 300) { // double-click = play
                        play(track);
                        return true;
                    }
                    lastClick = now;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    private static String fmt(float seconds) {
        int s = Math.round(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
