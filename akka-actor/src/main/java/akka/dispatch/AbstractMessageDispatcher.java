/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

abstract class AbstractMessageDispatcher {
    private volatile int _shutdownSchedule; // not initialized because this is faster: 0 == UNSCHEDULED
    protected final static AtomicIntegerFieldUpdater<AbstractMessageDispatcher> shutdownScheduleUpdater =
      AtomicIntegerFieldUpdater.newUpdater(AbstractMessageDispatcher.class, "_shutdownSchedule");

    private volatile long _inhabitants; // not initialized because this is faster
    protected final static AtomicLongFieldUpdater<AbstractMessageDispatcher> inhabitantsUpdater =
      AtomicLongFieldUpdater.newUpdater(AbstractMessageDispatcher.class, "_inhabitants");
}
