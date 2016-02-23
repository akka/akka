/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor;

import akka.util.Unsafe;

final class AbstractActorRef {
    final static long cellOffset;
    final static long lookupOffset;

    static {
        try {
          cellOffset = Unsafe.instance.objectFieldOffset(RepointableActorRef.class.getDeclaredField("_cellDoNotCallMeDirectly"));
          lookupOffset = Unsafe.instance.objectFieldOffset(RepointableActorRef.class.getDeclaredField("_lookupDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
