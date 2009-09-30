package se.scalablesolutions.akka.stm;

import static org.multiverse.api.StmUtils.retry;
import org.multiverse.api.Transaction;
import org.multiverse.api.exceptions.LoadUncommittedException;
import org.multiverse.api.exceptions.ReadonlyException;
import org.multiverse.datastructures.refs.ManagedRef;
import org.multiverse.stms.alpha.*;
import org.multiverse.stms.alpha.mixins.FastAtomicObjectMixin;
import static org.multiverse.utils.TransactionThreadLocal.getThreadLocalTransaction;

import static java.lang.String.format;

/**
 * A manual instrumented {@link org.multiverse.datastructures.refs.ManagedRef} implementation.
 * If this class is used, you don't need to worry about instrumentation/javaagents and
 * stuff like this.
 * <p/>
 * It is added to get the Akka project up and running, but probably will removed when the instrumentation
 * is 100% up and running and this can be done compiletime instead of messing with javaagents.
 *
 * @author Peter Veentjer
 */
public final class Ref<E> extends FastAtomicObjectMixin implements ManagedRef<E> {
    final public static class NoTransactionInScopeException extends RuntimeException {
    }

    public Ref() {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        ((AlphaTransaction) tx).attachNew(new RefTranlocal(Ref.this));
    }

    public Ref(Transaction t) {
        ((AlphaTransaction) t).attachNew(new RefTranlocal(Ref.this));
    }

    public Ref(final E value) {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        ((AlphaTransaction) tx).attachNew(new RefTranlocal(Ref.this, value));
    }

    public Ref(Transaction t, final E value) {
        ((AlphaTransaction) t).attachNew(new RefTranlocal(Ref.this, value));
    }

    public E get() {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) tx).privatize(Ref.this);
        return tranlocalRef.get();
    }

    public E get(Transaction t) {
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) t).privatize(Ref.this);
        return tranlocalRef.get();
    }

    public E getOrAwait() {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) tx).privatize(Ref.this);
        return tranlocalRef.getOrAwait();
    }

    public E getOrAwait(Transaction t) {
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) t).privatize(Ref.this);
        return tranlocalRef.getOrAwait();
    }

    public E set(final E newRef) {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) tx).privatize(Ref.this);
        return tranlocalRef.set(newRef);
    }

    public E set(Transaction t, final E newRef) {
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) t).privatize(Ref.this);
        return tranlocalRef.set(newRef);
    }

    public boolean isNull() {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) tx).privatize(Ref.this);
        return tranlocalRef.isNull();
    }

    public boolean isNull(Transaction t) {
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) t).privatize(Ref.this);
        return tranlocalRef.isNull();
    }

    public E clear() {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) tx).privatize(Ref.this);
        return tranlocalRef.clear();
    }

    public E clear(Transaction t) {
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) t).privatize(Ref.this);
        return tranlocalRef.clear();
    }

    @Override
    public String toString() {
        Transaction tx = getThreadLocalTransaction();
        if (tx == null) throw new NoTransactionInScopeException();
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) tx).privatize(Ref.this);
        return tranlocalRef.toString();
    }

    public String toString(Transaction t) {
        RefTranlocal<E> tranlocalRef = (RefTranlocal) ((AlphaTransaction) t).privatize(Ref.this);
        return tranlocalRef.toString();
    }

    public RefTranlocal<E> privatize(long readVersion) {
        RefTranlocal<E> origin = (RefTranlocal<E>) load(readVersion);
        if (origin == null) {
            throw new LoadUncommittedException();
        }
        return new RefTranlocal<E>(origin);
    }
}

class RefTranlocal<E> extends AlphaTranlocal {
    //field belonging to the stm.
    Ref atomicObject;
    RefTranlocal origin;

    E ref;

    RefTranlocal(RefTranlocal<E> origin) {
        this.version = origin.version;
        this.atomicObject = origin.atomicObject;
        this.ref = origin.ref;
        this.origin = origin;
    }

    RefTranlocal(Ref<E> owner) {
        this(owner, null);
    }

    RefTranlocal(Ref<E> owner, E ref) {
        this.version = Long.MIN_VALUE;
        this.atomicObject = owner;
        this.ref = ref;
    }

    @Override
    public AlphaAtomicObject getAtomicObject() {
        return atomicObject;
    }

    public E clear() {
        E oldValue = ref;
        ref = null;
        return oldValue;
    }

    public boolean isNull() {
        return ref == null;
    }

    public E get() {
        return ref;
    }

    public E set(E newValue) {
        if (committed) {
            throw new ReadonlyException();
        }
        E oldValue = ref;
        this.ref = newValue;
        return oldValue;
    }

    public E getOrAwait() {
        if (isNull()) {
            retry();
        }

        return ref;
    }

    @Override
    public String toString() {
        if (ref == null) {
            return "Ref(reference=null)";
        } else {
            return format("Ref(reference=%s)", ref);
        }
    }

    @Override
    public void prepareForCommit(long writeVersion) {
        this.version = writeVersion;
        this.committed = true;
        this.origin = null;
    }

    @Override
    public AlphaTranlocalSnapshot takeSnapshot() {
        return new RefTranlocalSnapshot<E>(this);
    }

    @Override
    public DirtinessStatus getDirtinessStatus() {
        if (committed) {
            return DirtinessStatus.committed;
        } else if (origin == null) {
            return DirtinessStatus.fresh;
        } else if (origin.ref != this.ref) {
            return DirtinessStatus.dirty;
        } else {
            return DirtinessStatus.clean;
        }
    }
}

class RefTranlocalSnapshot<E> extends AlphaTranlocalSnapshot {
    final RefTranlocal tranlocal;
    final E value;

    RefTranlocalSnapshot(RefTranlocal<E> tranlocal) {
        this.tranlocal = tranlocal;
        this.value = tranlocal.ref;
    }

    @Override
    public AlphaTranlocal getTranlocal() {
        return tranlocal;
    }

    @Override
    public void restore() {
        tranlocal.ref = value;
    }
}