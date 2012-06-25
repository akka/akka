/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

import akka.util.Unsafe;

final class AbstractActorRef {
    final static long cellOffset;

    static {
        try {
          cellOffset = Unsafe.instance.objectFieldOffset(RepointableActorRef.class.getDeclaredField("_cellDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
