package dev.custommusic.client;

import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.CMPayloads.BakeTrackPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Track chooser opened by right-clicking a blank disc. Double-click (or select + "Record")
 * bakes the chosen track into the disc via a server-validated payload.
 */
public class ChooseTrackScreen extends Screen {
    private TrackListWidget list;
    private Button recordButton;

    public ChooseTrackScreen() {
        super(Component.translatable("screen.custommusic.choose_track"));
    }

    @Override
    protected void init() {
        super.init();
        list = new TrackListWidget(minecraft, width, height, ClientTrackCache.available());
        addRenderableWidget(list);

        recordButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.custommusic.record"), b -> bake())
            .bounds(width / 2 - 100, height - 56, 200, 20).build());
        recordButton.active = false;

        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
            .bounds(width / 2 - 100, height - 32, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, title, width / 2, 22, 0xFFFFFFFF);
        if (ClientTrackCache.available().isEmpty()) {
            gui.drawCenteredString(font, Component.translatable("screen.custommusic.empty"),
                width / 2, height / 2 - 8, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void bake() {
        TrackEntry selected = list.getSelected() == null ? null : list.getSelected().track;
        if (selected != null) {
            bakeTrack(selected);
        }
    }

    private void bakeTrack(TrackEntry track) {
        PacketDistributor.sendToServer(new BakeTrackPayload(track.slug()));
        onClose();
    }

    private static String fmt(float seconds) {
        int s = Math.round(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private class TrackListWidget extends ObjectSelectionList<TrackListWidget.Entry> {
        TrackListWidget(Minecraft mc, int width, int height, List<TrackEntry> tracks) {
            super(mc, width, height, 48, 18);
            // y0=48, list height so that the bottom edge is height-64
            updateSizeAndPosition(width, height - 64 - 48, 48);
            for (TrackEntry track : tracks) {
                addEntry(new Entry(track)); // populated inside the widget: addEntry may be protected
            }
        }

        @Override
        public void setSelected(Entry entry) {
            super.setSelected(entry);
            recordButton.active = entry != null;
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
                var f = ChooseTrackScreen.this.font;
                Component title = Component.literal(track.title());
                Component details = Component.literal(track.slug() + "  ·  " + fmt(track.durationSeconds()))
                    .withStyle(ChatFormatting.GRAY);
                gui.drawString(f, title, left + width / 2 - f.width(title) / 2, top + 1, 0xFFFFFFFF, true);
                gui.drawString(f, details, left + width / 2 - f.width(details) / 2, top + 10, 0xFFFFFFFF, true);
            }

            @Override
            public Component getNarration() {
                return Component.literal(track.title());
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastClick < 300) { // double-click = bake
                        bakeTrack(track);
                        return true;
                    }
                    lastClick = now;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
    }
}
