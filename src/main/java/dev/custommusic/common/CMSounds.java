package dev.custommusic.common;

import dev.custommusic.CustomMusicMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The "slot pool": {@link #SLOT_COUNT} universal sound events registered through
 * DeferredRegister *before* the game starts (runtime SoundEvent registration is impossible —
 * the registry is frozen). Each scanned track deterministically gets one slot, and the client's
 * generated sounds.json maps {@code custommusic:slot_<n>} to the synced audio file. This keeps
 * playback 100% vanilla — no stream substitution, no SoundEngine mixins.
 */
public final class CMSounds {
    public static final int SLOT_COUNT = 128;

    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(Registries.SOUND_EVENT, CustomMusicMod.MODID);

    private static final List<DeferredHolder<SoundEvent, SoundEvent>> SLOTS = IntStream.range(0, SLOT_COUNT)
        .mapToObj(CMSounds::registerSlot)
        .toList();

    private static DeferredHolder<SoundEvent, SoundEvent> registerSlot(int i) {
        return SOUNDS.register(slotName(i), () -> SoundEvent.createVariableRangeEvent(CustomMusicMod.id(slotName(i))));
    }

    public static String slotName(int slot) {
        return "slot_" + slot;
    }

    /** Full id ("custommusic:slot_7") as used in sounds.json and jukebox_song json. */
    public static String slotId(int slot) {
        return CustomMusicMod.MODID + ":" + slotName(slot);
    }

    public static SoundEvent slotSound(int slot) {
        return SLOTS.get(slot).get();
    }

    private CMSounds() {
    }
}
