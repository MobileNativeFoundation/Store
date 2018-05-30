package com.nytimes.android.external.store3.base.impl;

import com.nytimes.android.external.store3.base.BasePersister;
import com.nytimes.android.external.store3.base.Clearable;
import com.nytimes.android.external.store3.base.RecordProvider;
import com.nytimes.android.external.store3.base.RecordState;

import javax.annotation.Nonnull;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.subjects.PublishSubject;

import static com.nytimes.android.external.store3.base.RecordState.STALE;

public final class StoreUtil {
    private StoreUtil() {
    }

    @Nonnull
    public static <Parsed, Key> ObservableTransformer<Parsed, Parsed>
    repeatWhenSubjectEmits(PublishSubject<Key> refreshSubject, @Nonnull final Key keyForRepeat) {
        Observable<Key> filter = refreshSubject.filter(key -> key.equals(keyForRepeat));
        return RepeatWhenEmits.from(filter);
    }

    public static <Raw, Key> boolean shouldReturnNetworkBeforeStale(
            BasePersister persister, StalePolicy stalePolicy, Key key) {
        return stalePolicy == StalePolicy.NETWORK_BEFORE_STALE
                && persisterIsStale(key, persister);
    }

    public static <Raw, Key> boolean persisterIsStale(@Nonnull Key key, BasePersister persister) {
        if (persister instanceof RecordProvider) {
            RecordProvider<Key> provider = (RecordProvider<Key>) persister;
            RecordState recordState = provider.getRecordState(key);
            return recordState == STALE;
        }
        return false;
    }

    public static <Raw, Key> void clearPersister(BasePersister persister, @Nonnull Key key) {
        boolean isPersisterClearable = persister instanceof Clearable;

        if (isPersisterClearable) {
            ((Clearable<Key>) persister).clear(key);
        }
    }
}
