/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.annotation.InternalApi
import akka.util.OptionVal

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object LoggerClass {

  // just to get access to the class context
  private final class TrickySecurityManager extends SecurityManager {
    def getClassStack: Array[Class[_]] = getClassContext
  }

  /**
   * Try to extract a logger class from the call stack, if not possible the provided default is used
   */
  def detectLoggerClassFromStack(default: Class[_]): Class[_] = {
    // TODO use stack walker API when we no longer need to support Java 8
    try {
      val trace = new TrickySecurityManager().getClassStack
      var suitableClass: OptionVal[Class[_]] = OptionVal.None
      var idx = 1 // skip this method/class and right away
      while (suitableClass.isEmpty && idx < trace.length) {
        val clazz = trace(idx)
        val name = clazz.getName
        if (!name.startsWith("scala.runtime") && !name.startsWith("akka.actor.typed.internal"))
          suitableClass = OptionVal.Some(clazz)
        idx += 1
      }

      suitableClass.getOrElse(default)
    } catch {
      case NonFatal(_) ⇒ default
    }
  }

}
