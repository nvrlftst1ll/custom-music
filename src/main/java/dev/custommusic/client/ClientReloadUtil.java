package dev.custommusic.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Triggers a client resource reload so the generated sounds.json is picked up by the
 * SoundManager. The method name differs across minor patch versions, so it is resolved
 * defensively; if that ever fails, the user can simply press F3+T.
 */
public final class ClientReloadUtil {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientReloadUtil() {
    }

    public static void reloadResources() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                Method m = findReloadMethod(mc.getClass());
                if (m != null) {
                    m.invoke(mc);
                    LOGGER.info("[CustomMusic] Client resources reloaded ({} track(s) available)",
                        ClientTrackCache.available().size());
                } else {
                    LOGGER.warn("[CustomMusic] Resource reload method not found — press F3+T to refresh sounds");
                }
            } catch (Exception e) {
                LOGGER.warn("[CustomMusic] Resource reload failed: {} — press F3+T to refresh sounds", e.toString());
            }
        });
    }

    private static Method findReloadMethod(Class<?> clazz) {
        for (String name : new String[] {"reloadResourcePacks", "reloadResources", "refreshResources"}) {
            try {
                Method m = clazz.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
