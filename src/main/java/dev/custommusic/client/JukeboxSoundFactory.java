package dev.custommusic.client;

import dev.custommusic.common.CMSounds;
import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.JukeboxPayloads.JukeboxStatePayload;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/**
 * Creates the positional, optionally looping sound instance for one playing Juke Box.
 *
 * <p>Vanilla couples loudness and hearing distance: the sound engine passes
 * {@code max(volume, 1.0) * sound.getAttenuationDistance()} as the OpenAL rolloff factor, and
 * the gain itself is clamped to {@code [0, 1]}. The only per-instance way to control the
 * hearing distance is therefore the {@link Sound}'s {@code attenuationDistance} — so this
 * factory wraps the resolved sound with a {@link RangedSound} that reports the user's range
 * slider. Result: the fade reaches zero exactly at {@code range} blocks, while the volume
 * slider (10–100%) controls the gain exactly.</p>
 */
final class JukeboxSoundFactory {
    private JukeboxSoundFactory() {
    }

    static SoundInstance create(TrackEntry entry, JukeboxStatePayload state) {
        ResourceLocation location = CMSounds.slotSound(entry.slot()).getLocation();
        BlockPos pos = state.pos();
        return new JukeboxSoundInstance(location, state.volume(), state.range(), state.loop(),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** One slot sound fixed at the block position, with looping and a custom hearing distance. */
    private static final class JukeboxSoundInstance extends AbstractSoundInstance {
        private final int rangeBlocks;
        private boolean resolved;

        JukeboxSoundInstance(ResourceLocation location, float volume, int rangeBlocks, boolean looping,
                             double x, double y, double z) {
            super(location, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.volume = volume;
            this.pitch = 1.0f;
            this.x = x;
            this.y = y;
            this.z = z;
            this.looping = looping;
            this.delay = 0;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.relative = false;
            this.rangeBlocks = rangeBlocks;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            WeighedSoundEvents events = super.resolve(soundManager);
            resolved = events != null;
            return events;
        }

        /** Wrap the resolved sound so its attenuation distance equals the user's range slider. */
        @Override
        public Sound getSound() {
            Sound base = super.getSound();
            return base != null && resolved ? new RangedSound(base, rangeBlocks) : base;
        }
    }

    /** Same sounds.json entry, but heard up to {@code rangeBlocks} blocks away. */
    private static final class RangedSound extends Sound {
        private final int rangeBlocks;

        RangedSound(Sound base, int rangeBlocks) {
            super(base.getLocation(), base.getVolume(), base.getPitch(), base.getWeight(),
                base.getType(), base.shouldStream(), base.shouldPreload(), base.getAttenuationDistance());
            this.rangeBlocks = rangeBlocks;
        }

        @Override
        public int getAttenuationDistance() {
            return rangeBlocks;
        }
    }
}
