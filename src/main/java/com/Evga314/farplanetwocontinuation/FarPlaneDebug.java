package com.Evga314.farplanetwocontinuation;

/**
 * Whether this is the debug build, which writes detailed performance and settings logs to latest.log. The debug jar carries a marker
 * file (see build.gradle, {@code -Pdebug_build}); the property {@code -Dfarplane.debug=true} turns it on in any build.
 */
public final class FarPlaneDebug {
    public static final boolean ENABLED = Boolean.getBoolean("farplane.debug")
        || FarPlaneDebug.class.getResource("/" + FarPlaneConstants.MOD_ID + "-debug.marker") != null;

    private FarPlaneDebug() {
    }
}
