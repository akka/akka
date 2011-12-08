/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor._
import akka.actor.DeploymentConfig._
import akka.event.EventStream
import com.typesafe.config._
import akka.config.ConfigurationException

object RemoteDeploymentConfig {

  case class RemoteScope(nodes: Iterable[RemoteAddress]) extends DeploymentConfig.Scope

}

class RemoteDeployer(_settings: ActorSystem.Settings, _eventStream: EventStream, _nodename: String)
  extends Deployer(_settings, _eventStream, _nodename) {

  import RemoteDeploymentConfig._

  override protected def lookupInConfig(path: String, configuration: Config = settings.config): Deploy = {
    import scala.collection.JavaConverters._
    import akka.util.ReflectiveAccess._

    val defaultDeploymentConfig = configuration.getConfig("akka.actor.deployment.default")

    // --------------------------------
    // akka.actor.deployment.<path>
    // --------------------------------
    val deploymentKey = "akka.actor.deployment." + path
    val deployment = configuration.getConfig(deploymentKey)

    val deploymentWithFallback = deployment.withFallback(defaultDeploymentConfig)

    val remoteNodes = deploymentWithFallback.getStringList("remote.nodes").asScala.toSeq

    // --------------------------------
    // akka.actor.deployment.<path>.remote
    // --------------------------------
    def parseRemote: Scope = {
      def raiseRemoteNodeParsingError() = throw new ConfigurationException(
        "Config option [" + deploymentKey +
          ".remote.nodes] needs to be a list with elements on format \"<hostname>:<port>\", was [" + remoteNodes.mkString(", ") + "]")

      val remoteAddresses = remoteNodes map (RemoteAddress(_, settings.name))

      RemoteScope(remoteAddresses)
    }

    val local = super.lookupInConfig(path, configuration)
    if (remoteNodes.isEmpty) local else local.copy(scope = parseRemote)
  }

}