/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch.affinity;

import akka.util.Unsafe;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import scala.util.control.NonFatal;
import static java.lang.invoke.MethodType.methodType;

final class OnSpinWait {
    private final static MethodHandle handle;

    public final static void spinWait() throws Throwable {
        handle.invokeExact();
    }

    static {
        final MethodHandle noop = MethodHandles.constant(Object.class, null).asType(methodType(Void.TYPE));
        MethodHandle impl;
        try {
          impl = MethodHandles.lookup().findStatic(Thread.class, "onSpinWait", methodType(Void.TYPE));
        } catch (NoSuchMethodException nsme) {
          impl = noop;
        } catch (IllegalAccessException iae) {
          impl = noop;
        }
        handle = impl;
  };
}