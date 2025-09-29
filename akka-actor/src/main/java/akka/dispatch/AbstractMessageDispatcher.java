/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

abstract class AbstractMessageDispatcher {
    final static VarHandle shutdownScheduleHandle;
    final static VarHandle inhabitantsHandle;

    static {
        try {
          MethodHandles.Lookup lookup =
              MethodHandles.privateLookupIn(MessageDispatcher.class, MethodHandles.lookup());
          shutdownScheduleHandle = lookup.unreflectVarHandle(MessageDispatcher.class.getDeclaredField("_shutdownScheduleDoNotCallMeDirectly"));
          inhabitantsHandle = lookup.unreflectVarHandle(MessageDispatcher.class.getDeclaredField("_inhabitantsDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
