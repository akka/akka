/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.sslconfig.akka.util

/** INTERNAL API */
final class AkkaLoggerFactory(system: ActorSystem) extends LoggerFactory {
  override def apply(clazz: Class[_]): NoDepsLogger = new AkkaLoggerBridge(system.eventStream, clazz)

  override def apply(name: String): NoDepsLogger = new AkkaLoggerBridge(system.eventStream, name, classOf[DummyClassForStringSources])
}
