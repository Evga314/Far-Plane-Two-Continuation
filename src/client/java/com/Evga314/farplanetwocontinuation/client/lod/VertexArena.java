package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The GPU memory of one layer's tiles: a few large buffers (blocks), every tile's vertices a part of one. Making a buffer is
 * usually instant, but now and then the driver has to take a new piece of video memory for it, and then one call stalls the frame
 * for hundreds of milliseconds (seen on phones and on desktop OpenGL alike). With blocks that happens only a few times, while the
 * world loads (see {@link #reserve}) or ahead of need ({@link #growAhead}), and blocks are kept until the game closes.
 * <p>
 * Free space is kept per block as ranges that merge with their neighbours; a tile takes the smallest range it fits in, so large
 * ranges stay for large tiles. Render thread only. The GPU runs the copies and draws in the order they are sent, so a range freed
 * by a tile no longer drawn can be written again at once.
 */
final class VertexArena implements AutoCloseable {
    /** Every part starts at a multiple of this, which suits any vertex format and any copy. */
    private static final int ALIGN = 256;
    /** A new block is made ahead of need at most this often. */
    private static final long GROW_AHEAD_NS = 2_000_000_000L;

    private final String name;
    private final int blockBytes;
    private final List<Block> blocks = new ArrayList<>();
    private long lastGrow;
    /** Bytes free in all blocks. */
    private long free;

    VertexArena(String name, int blockBytes) {
        this.name = name;
        this.blockBytes = blockBytes;
    }

    /** Makes blocks until there are {@code count}; meant for while the world loads, when a stalled frame is not seen. */
    void reserve(int count) {
        while (this.blocks.size() < count) {
            this.addBlock();
        }
    }

    /**
     * Gives the blocks beyond the first {@code keep} back to the driver where nothing is in them, for when less memory is wanted: a
     * lower limit in the settings, or leaving the world.
     */
    void trim(int keep) {
        for (int i = this.blocks.size() - 1; i >= keep; i--) {
            Block block = this.blocks.get(i);
            Integer first = block.free.get(0);
            if (first == null || first != this.blockBytes) {
                continue;
            }
            block.buffer.close();
            this.blocks.remove(i);
            this.free -= this.blockBytes;
        }
    }

    int blockBytes() {
        return this.blockBytes;
    }

    /**
     * Makes one more block when less than half a block is free, at most every few seconds: the stall then comes before the space is
     * needed, not when a tile is waiting for it.
     */
    void growAhead() {
        long now = System.nanoTime();
        if (this.free < this.blockBytes / 2 && now - this.lastGrow > GROW_AHEAD_NS) {
            this.lastGrow = now;
            this.addBlock();
        }
    }

    /** A part of {@code bytes} for a tile's vertices. Tiles larger than a block get a buffer of their own. */
    Part allocate(int bytes) {
        int size = align(bytes);
        if (size > this.blockBytes) {
            long start = PerfLog.ON ? System.nanoTime() : 0L;
            GpuBuffer own = RenderSystem.getDevice().createBuffer(
                () -> "FarPlane " + this.name + " large tile", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size
            );
            if (PerfLog.ON) {
                PerfLog.bufferCreated(System.nanoTime() - start, size);
            }
            return new Part(null, own, 0, size);
        }
        Part part = this.take(size);
        if (part == null) {
            this.addBlock();
            part = this.take(size);
        }
        return part;
    }

    /** For the debug screen: blocks and the share of them free. */
    String stats() {
        return this.blocks.size() + " blocks " + ((long) this.blocks.size() * this.blockBytes >> 20) + " MB, free " + (this.free >> 20) + " MB";
    }

    @Override
    public void close() {
        for (Block block : this.blocks) {
            block.buffer.close();
        }
        this.blocks.clear();
        this.free = 0L;
    }

    private static int align(int bytes) {
        return (Math.max(bytes, 1) + ALIGN - 1) & -ALIGN;
    }

    private void addBlock() {
        long start = PerfLog.ON ? System.nanoTime() : 0L;
        int index = this.blocks.size();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "FarPlane " + this.name + " tiles #" + index, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, this.blockBytes
        );
        if (PerfLog.ON) {
            PerfLog.bufferCreated(System.nanoTime() - start, this.blockBytes);
        }
        Block block = new Block(buffer);
        block.free.put(0, this.blockBytes);
        this.blocks.add(block);
        this.free += this.blockBytes;
        this.lastGrow = System.nanoTime();
    }

    /** The smallest free range of all blocks that fits, or null. */
    private @Nullable Part take(int size) {
        Block bestBlock = null;
        int bestOffset = 0;
        int bestSize = Integer.MAX_VALUE;
        for (Block block : this.blocks) {
            for (Map.Entry<Integer, Integer> range : block.free.entrySet()) {
                int rangeSize = range.getValue();
                if (rangeSize >= size && rangeSize < bestSize) {
                    bestBlock = block;
                    bestOffset = range.getKey();
                    bestSize = rangeSize;
                    if (rangeSize == size) {
                        break;
                    }
                }
            }
            if (bestSize == size) {
                break;
            }
        }
        if (bestBlock == null) {
            return null;
        }
        bestBlock.free.remove(bestOffset);
        if (bestSize > size) {
            bestBlock.free.put(bestOffset + size, bestSize - size);
        }
        this.free -= size;
        return new Part(bestBlock, bestBlock.buffer, bestOffset, size);
    }

    private void release(Part part) {
        Block block = part.block;
        if (block == null) {
            part.buffer.close();
            return;
        }
        if (!this.blocks.contains(block)) {
            // Freed after the arena was closed.
            return;
        }
        int offset = part.offset;
        int size = part.size;
        Map.Entry<Integer, Integer> before = block.free.floorEntry(offset);
        if (before != null && before.getKey() + before.getValue() == offset) {
            offset = before.getKey();
            size += before.getValue();
            block.free.remove(before.getKey());
        }
        Integer after = block.free.get(part.offset + part.size);
        if (after != null) {
            block.free.remove(part.offset + part.size);
            size += after;
        }
        block.free.put(offset, size);
        this.free += part.size;
    }

    private static final class Block {
        final GpuBuffer buffer;
        /** Free ranges, offset to size, never touching each other. */
        final TreeMap<Integer, Integer> free = new TreeMap<>();

        Block(GpuBuffer buffer) {
            this.buffer = buffer;
        }
    }

    /** One tile's vertices in a block, or in a buffer of their own. */
    final class Part {
        private final @Nullable Block block;
        final GpuBuffer buffer;
        final int offset;
        final int size;
        private boolean closed;
        private @Nullable GpuBufferSlice slice;

        private Part(@Nullable Block block, GpuBuffer buffer, int offset, int size) {
            this.block = block;
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
        }

        boolean isClosed() {
            return this.closed;
        }

        /** {@code length} bytes from {@code start} within this part. */
        GpuBufferSlice slice(int start, int length) {
            return this.buffer.slice(this.offset + start, length);
        }

        /** The whole part, made once. */
        GpuBufferSlice slice() {
            if (this.slice == null) {
                this.slice = this.buffer.slice(this.offset, this.size);
            }
            return this.slice;
        }

        void close() {
            if (!this.closed) {
                this.closed = true;
                VertexArena.this.release(this);
            }
        }
    }
}
