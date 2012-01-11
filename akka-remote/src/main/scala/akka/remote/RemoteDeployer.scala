/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor._
import akka.routing._
import com.typesafe.config._
import akka.config.ConfigurationException

case class RemoteScope(node: UnparsedSystemAddress[UnparsedTransportAddress]) extends Scope

class RemoteDeployer(_settings: ActorSystem.Settings) extends Deployer(_settings) {

  override protected def parseConfig(path: String, config: Config): Option[Deploy] = {
    import scala.collection.JavaConverters._
    import akka.util.ReflectiveAccess._

    super.parseConfig(path, config) match {
      case d @ Some(deploy) ⇒
        deploy.config.getString("remote") match {
          case RemoteAddressExtractor(r) ⇒ Some(deploy.copy(scope = RemoteScope(r)))
          case str ⇒
            if (!str.isEmpty) throw new ConfigurationException("unparseable remote node name " + str)
            val nodes = deploy.config.getStringList("target.nodes").asScala
            if (nodes.isEmpty || deploy.routing == NoRouter) d
            else {
              val r = deploy.routing match {
                case RoundRobinRouter(x, _, resizer)                     ⇒ RemoteRoundRobinRouter(x, nodes, resizer)
                case RandomRouter(x, _, resizer)                         ⇒ RemoteRandomRouter(x, nodes, resizer)
                case SmallestMailboxRouter(x, _, resizer)                ⇒ RemoteSmallestMailboxRouter(x, nodes, resizer)
                case BroadcastRouter(x, _, resizer)                      ⇒ RemoteBroadcastRouter(x, nodes, resizer)
                case ScatterGatherFirstCompletedRouter(x, _, w, resizer) ⇒ RemoteScatterGatherFirstCompletedRouter(x, nodes, w, resizer)
              }
              Some(deploy.copy(routing = r))
            }
        }
      case None ⇒ None
    }
  }

}