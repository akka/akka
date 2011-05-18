package akka.cluster

/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
import akka.util.ReflectiveAccess.ClusterModule
import akka.config.Config

/**
 * Node address holds the node name and the cluster name and can be used as a hash lookup key for a Node instance.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class NodeAddress(
    val clusterName: String,
    val nodeName: String,
    val hostname: String,
    val port: Int) {
  if ((hostname eq null)    || hostname == "")    throw new NullPointerException("Host name must not be null or empty string")
  if ((nodeName eq null)    || nodeName == "")    throw new NullPointerException("Node name must not be null or empty string")
  if ((clusterName eq null) || clusterName == "") throw new NullPointerException("Cluster name must not be null or empty string")
  if (port < 1)                                   throw new NullPointerException("Port can not be negative")

  override def toString             = "%s:%s:%s:%s".format(clusterName, nodeName, hostname, port)

  override def hashCode             = 0 + clusterName.## + nodeName.## + hostname.## + port.##

  override def equals(other: Any)   = NodeAddress.unapply(this) == NodeAddress.unapply(other)
}

object NodeAddress {

  def apply(
      clusterName: String = ClusterModule.name,
      nodeName: String    = Config.nodename,
      hostname: String    = Config.hostname,
      port: Int           = Config.remoteServerPort): NodeAddress =
    new NodeAddress(clusterName, nodeName, hostname, port)

  def unapply(other: Any) = other match {
    case address: NodeAddress => Some((address.clusterName, address.nodeName, address.hostname, address.port))
    case _                    => None
  }
}
