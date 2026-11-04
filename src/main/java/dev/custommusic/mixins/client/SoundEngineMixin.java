package dev.custommusic.mixins.client;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.client.ClientTrackCache;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Purely diagnostic: when one of our slot sounds plays but this client does not know the
 * track yet (still syncing, or the pack was not reloaded), log a helpful hint instead of
 * vanilla's cryptic "Unable to play unknown soundEvent". This is the only mixin in the mod —
 * the slot-pool + virtual-pack architecture deliberately avoids touching the sound engine.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
    private void custommusic$hintUnsyncedTrack(SoundInstance instance, CallbackInfo ci) {
        var location = instance.getLocation();
        if (!location.getNamespace().equals(CustomMusicMod.MODID)) return;
        String path = location.getPath();
        if (!path.startsWith("slot_")) return;
        try {
            int slot = Integer.parseInt(path.substring("slot_".length()));
            if (!ClientTrackCache.hasSlot(slot)) {
                ClientTrackCache.noteUnknownSlot(slot, location);
            }
        } catch (NumberFormatException ignored) {
        }
    }
}
