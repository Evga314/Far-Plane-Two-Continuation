package com.Evga314.farplanetwocontinuation.tile;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Compression for tiles and cache files with one deflater and one inflater per thread instead of a new one, with native memory of
 * its own, for every tile. The streams leave them open; each is reset before it is handed out again. The output is the same.
 */
public final class Zip {
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(Deflater::new);
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);

    private Zip() {
    }

    public static DeflaterOutputStream compress(OutputStream out) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        return new DeflaterOutputStream(out, deflater);
    }

    public static InflaterInputStream decompress(InputStream in) {
        Inflater inflater = INFLATER.get();
        inflater.reset();
        return new InflaterInputStream(in, inflater);
    }
}
