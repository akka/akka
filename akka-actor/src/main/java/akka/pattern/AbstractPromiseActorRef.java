/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern;

import akka.util.Unsafe;

final class AbstractPromiseActorRef {
    final static long stateOffset;

    static {
        try {
            stateOffset = Unsafe.instance.objectFieldOffset(PromiseActorRef.class.getDeclaredField("_stateDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}