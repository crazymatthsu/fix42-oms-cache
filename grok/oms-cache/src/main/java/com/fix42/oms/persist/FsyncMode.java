package com.fix42.oms.persist;

public enum FsyncMode {
    /** Force the WAL after every appended record. Default for unreproducible drop copy. */
    EVERY_RECORD,
    /** Force every N records or T milliseconds (see {@link PersistenceConfig}). */
    GROUP,
    /** Rely on the OS page cache. Tests / development only. */
    NONE
}
