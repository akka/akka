/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

abstract class AbstractMailbox {
    private volatile int _status = Mailbox.Idle();
    protected final static AtomicIntegerFieldUpdater<AbstractMailbox> updater = AtomicIntegerFieldUpdater.newUpdater(AbstractMailbox.class, "_status");
}