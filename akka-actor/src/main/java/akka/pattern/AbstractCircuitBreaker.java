/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern;

import akka.util.Unsafe;

class AbstractCircuitBreaker {
    protected final static long stateOffset;

    static {
        try {
            stateOffset = Unsafe.instance.objectFieldOffset(CircuitBreaker.class.getDeclaredField("_currentStateDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
