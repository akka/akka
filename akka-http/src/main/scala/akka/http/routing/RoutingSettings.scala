/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import com.typesafe.config.Config
import akka.actor.ActorRefFactory
import akka.http.util._

case class RoutingSettings(verboseErrorMessages: Boolean)

object RoutingSettings extends SettingsCompanion[RoutingSettings]("akka.http.routing") {
  def fromSubConfig(c: Config) = apply(
    c getBoolean "verbose-error-messages")

  implicit def default(implicit refFactory: ActorRefFactory) =
    apply(actorSystem)
}
