/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import akka.util.Unsafe;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

abstract class AbstractMessageDispatcher {
    final static long shutdownScheduleOffset;
    final static long inhabitantsOffset;

    static {
        try {
          shutdownScheduleOffset = Unsafe.instance.objectFieldOffset(MessageDispatcher.class.getDeclaredField("_shutdownScheduleDoNotCallMeDirectly"));
          inhabitantsOffset = Unsafe.instance.objectFieldOffset(MessageDispatcher.class.getDeclaredField("_inhabitantsDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
