/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

abstract class AbstractMailbox {
    private volatile int _status; // not initialized because this is faster: 0 == Open
    protected final static AtomicIntegerFieldUpdater<AbstractMailbox> updater = AtomicIntegerFieldUpdater.newUpdater(AbstractMailbox.class, "_status");
}
