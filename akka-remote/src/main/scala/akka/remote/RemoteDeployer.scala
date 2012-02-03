/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor._
import akka.routing._
import com.typesafe.config._
import akka.config.ConfigurationException

case class RemoteScope(node: Address) extends Scope {
  def withFallback(other: Scope): Scope = this
}

class RemoteDeployer(_settings: ActorSystem.Settings, _classloader: ClassLoader) extends Deployer(_settings, _classloader) {

  override protected def parseConfig(path: String, config: Config): Option[Deploy] = {
    import scala.collection.JavaConverters._

    super.parseConfig(path, config) match {
      case d @ Some(deploy) ⇒
        deploy.config.getString("remote") match {
          case AddressExtractor(r) ⇒ Some(deploy.copy(scope = RemoteScope(r)))
          case str ⇒
            if (!str.isEmpty) throw new ConfigurationException("unparseable remote node name " + str)
            val nodes = deploy.config.getStringList("target.nodes").asScala
            if (nodes.isEmpty || deploy.routerConfig == NoRouter) d
            else Some(deploy.copy(routerConfig = RemoteRouterConfig(deploy.routerConfig, nodes)))
        }
      case None ⇒ None
    }
  }

}