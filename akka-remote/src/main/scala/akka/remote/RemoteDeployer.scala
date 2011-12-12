/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor._
import akka.event.EventStream
import com.typesafe.config._
import akka.config.ConfigurationException

case class RemoteScope(node: UnparsedSystemAddress[UnparsedTransportAddress]) extends Scope

class RemoteDeployer(_settings: ActorSystem.Settings) extends Deployer(_settings) {

  override protected def parseConfig(path: String, config: Config): Option[Deploy] = {
    import scala.collection.JavaConverters._
    import akka.util.ReflectiveAccess._

    val deployment = config.withFallback(default)

    val transform: Deploy ⇒ Deploy =
      if (deployment.hasPath("remote")) deployment.getString("remote") match {
        case RemoteAddressExtractor(r) ⇒ (d ⇒ d.copy(scope = RemoteScope(r)))
        case x                         ⇒ identity
      }
      else identity

    super.parseConfig(path, config) map transform
  }

}