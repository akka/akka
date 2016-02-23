/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.pattern;

import akka.util.Unsafe;

final class AbstractPromiseActorRef {
    final static long stateOffset;
    final static long watchedByOffset;

    static {
        try {
            stateOffset = Unsafe.instance.objectFieldOffset(PromiseActorRef.class.getDeclaredField("_stateDoNotCallMeDirectly"));
            watchedByOffset = Unsafe.instance.objectFieldOffset(PromiseActorRef.class.getDeclaredField("_watchedByDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
