/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch;

import akka.util.Unsafe;

abstract class AbstractMessageDispatcher {
  static final long shutdownScheduleOffset;
  static final long inhabitantsOffset;

  static {
    try {
      shutdownScheduleOffset =
          Unsafe.instance.objectFieldOffset(
              MessageDispatcher.class.getDeclaredField("_shutdownScheduleDoNotCallMeDirectly"));
      inhabitantsOffset =
          Unsafe.instance.objectFieldOffset(
              MessageDispatcher.class.getDeclaredField("_inhabitantsDoNotCallMeDirectly"));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }
}
