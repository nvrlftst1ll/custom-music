package dev.custommusic.common;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An in-memory {@link PackResources}. Keys are full pack-relative paths such as
 * {@code data/custommusic/jukebox_song/foo.json} or
 * {@code assets/custommusic/sounds/tracks/foo.ogg}; values are suppliers, so big audio
 * files stream lazily from disk instead of sitting in RAM.
 */
public final class VirtualPackResources implements PackResources {
    private final String id;
    private final Component description;
    private final int packFormat;
    private final Set<String> namespaces;
    private final Map<String, Supplier<InputStream>> files;

    public VirtualPackResources(String id, Component description, int packFormat,
                                Set<String> namespaces, Map<String, Supplier<InputStream>> files) {
        this.id = id;
        this.description = description;
        this.packFormat = packFormat;
        this.namespaces = Set.copyOf(namespaces);
        this.files = new HashMap<>(files);
    }

    @Override
    public String packId() {
        return id;
    }

    @Override
    public PackLocationInfo location() {
        return new PackLocationInfo(id, description, PackSource.BUILT_IN, Optional.empty());
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return namespaces;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        Supplier<InputStream> supplier = files.get(virtualPath(type, location));
        return supplier == null ? null : supplier::get;
    }

    @Override
    public void listResources(PackType type, String namespace, String path,
                              PackResources.ResourceOutput output) {
        String dirPrefix = type.getDirectory() + "/" + namespace + "/";
        String prefix = path.isEmpty() ? dirPrefix : dirPrefix + path + "/";
        for (Map.Entry<String, Supplier<InputStream>> e : files.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix)) continue;
            String relative = key.substring(dirPrefix.length());
            ResourceLocation loc = ResourceLocation.tryBuild(namespace, relative);
            if (loc != null) {
                Supplier<InputStream> supplier = e.getValue();
                output.accept(loc, supplier::get);
            }
        }
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        String joined = String.join("/", paths);
        if ("pack.mcmeta".equals(joined)) {
            String meta = "{\"pack\":{\"pack_format\":" + packFormat
                + ",\"description\":\"" + escape(description.getString()) + "\"}}";
            return () -> new ByteArrayInputStream(meta.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        if ("pack".equals(serializer.getMetadataSectionName())) {
            // The vanilla 3-arg constructor works in dev and production jars alike
            // (the 2-arg convenience ctor only exists in the dev/patched classpath).
            return (T) new PackMetadataSection(description, packFormat, Optional.empty());
        }
        return null;
    }

    @Override
    public void close() {
    }

    private static String virtualPath(PackType type, ResourceLocation location) {
        return type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
