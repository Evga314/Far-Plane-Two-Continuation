package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.Iterator;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The sections Sodium draws in the frame being drawn: its render lists, as they come out of its culling, also when it culls on a
 * thread of its own (as on phones), where a section is drawn a frame or more after it is built. Only touched when Sodium is loaded.
 * Sodium's internals: should they change, {@link #collect} returns false and the mod asks whether sections are built instead.
 */
final class SodiumFrame {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable Field managerField;
    private static boolean failed;

    private SodiumFrame() {
    }

    /** Fills {@code out} with the sections Sodium draws in this frame, as SectionPos longs; false when that cannot be found out. */
    static boolean collect(Object renderer, LongOpenHashSet out) {
        out.clear();
        if (failed) {
            return false;
        }
        try {
            Field field = managerField;
            if (field == null) {
                field = SodiumWorldRenderer.class.getDeclaredField("renderSectionManager");
                field.setAccessible(true);
                managerField = field;
            }
            RenderSectionManager manager = (RenderSectionManager) field.get(renderer);
            if (manager == null) {
                return false;
            }
            Iterator<ChunkRenderList> lists = manager.getRenderLists().iterator(false);
            while (lists.hasNext()) {
                ChunkRenderList list = lists.next();
                RenderRegion region = list.getRegion();
                ByteIterator sections = list.sectionsWithGeometryIterator(false);
                if (sections == null) {
                    continue;
                }
                while (sections.hasNext()) {
                    int index = sections.nextByteAsInt();
                    out.add(SectionPos.asLong(
                        region.getChunkX() + LocalSectionIndex.unpackX(index),
                        region.getChunkY() + LocalSectionIndex.unpackY(index),
                        region.getChunkZ() + LocalSectionIndex.unpackZ(index)
                    ));
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            LOGGER.warn("FarPlane could not read what Sodium draws in a frame; it asks whether sections are built instead", e);
            return false;
        }
    }
}
