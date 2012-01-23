/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

abstract class AbstractPromise {
    private volatile Object _ref = DefaultPromise.EmptyPending();
    protected final static AtomicReferenceFieldUpdater<AbstractPromise, Object> updater =
            AtomicReferenceFieldUpdater.newUpdater(AbstractPromise.class, Object.class, "_ref");
}
