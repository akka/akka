/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package com.typesafe.sslconfig.akka.util

import akka.actor.ActorSystem
import akka.event.{ DummyClassForStringSources, EventStream }
import akka.event.Logging._
import com.typesafe.sslconfig.util.{ LoggerFactory, NoDepsLogger }

final class AkkaLoggerFactory(system: ActorSystem) extends LoggerFactory {
  override def apply(clazz: Class[_]): NoDepsLogger = new AkkaLoggerBridge(system.eventStream, clazz)

  override def apply(name: String): NoDepsLogger =
    new AkkaLoggerBridge(system.eventStream, name, classOf[DummyClassForStringSources])
}

class AkkaLoggerBridge(bus: EventStream, logSource: String, logClass: Class[_]) extends NoDepsLogger {
  def this(bus: EventStream, clazz: Class[_]) { this(bus, clazz.getCanonicalName, clazz) }

  override def isDebugEnabled: Boolean = true

  override def debug(msg: String): Unit = bus.publish(Debug(logSource, logClass, msg))

  override def info(msg: String): Unit = bus.publish(Info(logSource, logClass, msg))

  override def warn(msg: String): Unit = bus.publish(Warning(logSource, logClass, msg))

  override def error(msg: String): Unit = bus.publish(Error(logSource, logClass, msg))
  override def error(msg: String, throwable: Throwable): Unit = bus.publish(Error(logSource, logClass, msg))

}
