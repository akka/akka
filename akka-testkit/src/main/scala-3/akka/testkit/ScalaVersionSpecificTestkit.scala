/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import akka.actor.ActorSystem

import ScalaVersionSpecificTestkit._

abstract class ScalaVersionSpecificTestkit(_system: ActorSystem) extends EarlyInit(using _system) with TestKitBase
object ScalaVersionSpecificTestkit {
  private[testkit] trait EarlyInit(implicit val system: ActorSystem)
}
