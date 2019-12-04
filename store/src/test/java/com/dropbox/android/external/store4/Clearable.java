package com.dropbox.android.external.store4;


import javax.annotation.Nonnull;

/**
 * Persisters should implement Clearable if they want store.clear(key) to also clear the persister
 *
 * @param <T> Type of key/request param in store
 */
@Deprecated//used in tests
public interface Clearable<T> {
    void clear(@Nonnull T key);
}
