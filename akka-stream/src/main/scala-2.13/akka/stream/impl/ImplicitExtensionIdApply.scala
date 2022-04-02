/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.actor.ActorSystem
import akka.actor.ExtensionId
import akka.annotation.DoNotInherit
import akka.stream.scaladsl.Tcp
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.annotation.nowarn

/*
 * Some extensions here provide an apply that takes an implicit actor system which needs different slightly syntax to define
 * on Scala 2 and Scala 3
 */

/**
 * Not for user extension
 */
@DoNotInherit
trait TcpImplicitExtensionIdApply extends ExtensionId[Tcp] {
  def apply()(implicit system: ActorSystem): Tcp = super.apply(system)
}

/**
 * Not for user extension
 */
@DoNotInherit
@nowarn("msg=deprecated")
trait AkkaSSLConfigExtensionIdApply extends ExtensionId[AkkaSSLConfig] {
  def apply()(implicit system: ActorSystem): AkkaSSLConfig = super.apply(system)
}
