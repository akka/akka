/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster

import akka.remoteinterface.RemoteSupport
import akka.serialization.Serializer
import akka.actor._
import DeploymentConfig._
import akka.dispatch.Future
import akka.config.Config
import akka.util._
import akka.routing.RouterType
import akka.AkkaException

import com.eaio.uuid.UUID

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ConcurrentSkipListSet, ConcurrentHashMap }

import scala.collection.mutable.ConcurrentMap
import scala.collection.JavaConversions._

class ClusterException(message: String) extends AkkaException(message)

object ChangeListener {

  /**
   * Cluster membership change listener.
   * For Scala API.
   */
  trait ChangeListener {
    def notify(event: ChangeNotification, client: ClusterNode) {
      event match {
        case NodeConnected(name)     ⇒ nodeConnected(name, client)
        case NodeDisconnected(name)  ⇒ nodeDisconnected(name, client)
        case NewLeader(name: String) ⇒ newLeader(name, client)
        case NewSession              ⇒ thisNodeNewSession(client)
        case ThisNode.Connected      ⇒ thisNodeConnected(client)
        case ThisNode.Disconnected   ⇒ thisNodeDisconnected(client)
        case ThisNode.Expired        ⇒ thisNodeExpired(client)
      }
    }

    def nodeConnected(node: String, client: ClusterNode) {}

    def nodeDisconnected(node: String, client: ClusterNode) {}

    def newLeader(name: String, client: ClusterNode) {}

    def thisNodeNewSession(client: ClusterNode) {}

    def thisNodeConnected(client: ClusterNode) {}

    def thisNodeDisconnected(client: ClusterNode) {}

    def thisNodeExpired(client: ClusterNode) {}
  }

  /**
   * Cluster membership change listener.
   * For Java API.
   */
  abstract class ChangeListenerAdapter extends ChangeListener

  sealed trait ChangeNotification

  case class NodeConnected(node: String) extends ChangeNotification

  case class NodeDisconnected(node: String) extends ChangeNotification

  case class NewLeader(name: String) extends ChangeNotification

  case object NewSession extends ChangeNotification

  object ThisNode {

    case object Connected extends ChangeNotification

    case object Disconnected extends ChangeNotification

    case object Expired extends ChangeNotification

  }
}

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
  if ((hostname eq null) || hostname == "") throw new NullPointerException("Host name must not be null or empty string")
  if ((nodeName eq null) || nodeName == "") throw new NullPointerException("Node name must not be null or empty string")
  if ((clusterName eq null) || clusterName == "") throw new NullPointerException("Cluster name must not be null or empty string")
  if (port < 1) throw new NullPointerException("Port can not be negative")

  override def toString = "%s:%s:%s:%s".format(clusterName, nodeName, hostname, port)

  override def hashCode = 0 + clusterName.## + nodeName.## + hostname.## + port.##

  override def equals(other: Any) = NodeAddress.unapply(this) == NodeAddress.unapply(other)
}

object NodeAddress {

  def apply(
    clusterName: String = Config.clusterName,
    nodeName: String = Config.nodename,
    hostname: String = Config.hostname,
    port: Int = Config.remoteServerPort): NodeAddress = new NodeAddress(clusterName, nodeName, hostname, port)

  def unapply(other: Any) = other match {
    case address: NodeAddress ⇒ Some((address.clusterName, address.nodeName, address.hostname, address.port))
    case _                    ⇒ None
  }
}

/**
 * Interface for cluster node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ClusterNode {
  import ChangeListener._

  def nodeAddress: NodeAddress
  def zkServerAddresses: String

  def remoteClientLifeCycleListener: ActorRef
  def remoteDaemon: ActorRef
  def remoteService: RemoteSupport
  def remoteServerAddress: InetSocketAddress

  val isConnected = new Switch(false)
  val isLeader = new AtomicBoolean(false)
  val electionNumber = new AtomicInteger(Int.MaxValue)

  private[cluster] val locallyCachedMembershipNodes = new ConcurrentSkipListSet[String]()
  private[cluster] val nodeNameToAddress: ConcurrentMap[String, InetSocketAddress] = new ConcurrentHashMap[String, InetSocketAddress]
  private[cluster] val locallyCheckedOutActors: ConcurrentMap[UUID, Array[Byte]] = new ConcurrentHashMap[UUID, Array[Byte]]

  def membershipNodes: Array[String]

  def isRunning: Boolean = isConnected.isOn

  def start(): ClusterNode

  def shutdown()

  def disconnect(): ClusterNode

  def reconnect(): ClusterNode

  /**
   * Registers a cluster change listener.
   */
  def register(listener: ChangeListener): ClusterNode

  /**
   * Returns the name of the current leader.
   */
  def leader: String

  /**
   * Explicitly resign from being a leader. If this node is not a leader then this operation is a no-op.
   */
  def resign()

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], format: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationScheme: ReplicationScheme, format: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, format: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, replicationScheme: ReplicationScheme, format: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationScheme: ReplicationScheme, serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, format: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationScheme: ReplicationScheme, format: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, format: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, replicationScheme: ReplicationScheme, format: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, serializeMailbox: Boolean, format: AnyRef): ClusterNode

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, format: AnyRef): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, format: Serializer): ClusterNode

  /**
   * Removes actor with uuid from the cluster.
   */
  def remove(uuid: UUID)

  /**
   * Removes actor with address from the cluster.
   */
  def remove(address: String): ClusterNode

  /**
   * Is the actor with uuid clustered or not?
   */
  def isClustered(actorAddress: String): Boolean

  /**
   * Is the actor with uuid in use on 'this' node or not?
   */
  def isInUseOnNode(actorAddress: String): Boolean

  /**
   * Is the actor with uuid in use or not?
   */
  def isInUseOnNode(actorAddress: String, node: NodeAddress): Boolean

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String): Option[ActorRef]

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String, format: Serializer): Option[ActorRef]

  /**
   * Using (checking out) all actors with a specific UUID on all nodes in the cluster.
   */
  def useActorOnAllNodes(uuid: UUID)

  /**
   * Using (checking out) specific UUID on a specefic node.
   */
  def useActorOnNode(node: String, uuid: UUID)

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorAddress: String)

  /**
   * Releases (checking in) all actors with a specific UUID on all nodes in the cluster where the actor is in 'use'.
   */
  def releaseActorOnAllNodes(uuid: UUID)

  /**
   * Creates an ActorRef with a Router to a set of clustered actors.
   */
  def ref(actorAddress: String, router: RouterType): ActorRef

  /**
   * Migrate the actor from 'this' node to node 'to'.
   */
  def migrate(to: NodeAddress, actorAddress: String)

  /**
   * Migrate the actor from node 'from' to node 'to'.
   */
  def migrate(from: NodeAddress, to: NodeAddress, actorAddress: String)

  /**
   * Returns the UUIDs of all actors checked out on this node.
   */
  def uuidsForActorsInUse: Array[UUID]

  /**
   * Returns the addresses of all actors checked out on this node.
   */
  def addressesForActorsInUse: Array[String]

  /**
   * Returns the UUIDs of all actors registered in this cluster.
   */
  def uuidsForClusteredActors: Array[UUID]

  /**
   * Returns the addresses of all actors registered in this cluster.
   */
  def addressesForClusteredActors: Array[String]

  /**
   * Returns the actor id for the actor with a specific UUID.
   */
  def actorAddressForUuid(uuid: UUID): Option[String]

  /**
   * Returns the actor ids for all the actors with a specific UUID.
   */
  def actorAddressForUuids(uuids: Array[UUID]): Array[String]

  /**
   * Returns the actor UUIDs for actor ID.
   */
  def uuidsForActorAddress(actorAddress: String): Array[UUID]

  /**
   * Returns the node names of all actors in use with UUID.
   */
  def nodesForActorsInUseWithUuid(uuid: UUID): Array[String]

  /**
   * Returns the UUIDs of all actors in use registered on a specific node.
   */
  def uuidsForActorsInUseOnNode(nodeName: String): Array[UUID]

  /**
   * Returns the addresses of all actors in use registered on a specific node.
   */
  def addressesForActorsInUseOnNode(nodeName: String): Array[String]

  /**
   * Returns Format for actor with UUID.
   */
  def formatForActor(actorAddress: String): Serializer

  /**
   * Returns home address for actor with UUID.
   */
  def addressesForActor(actorAddress: String): Array[(UUID, InetSocketAddress)]

  /**
   * Send a function 'Function0[Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   */
  def send(f: Function0[Unit], replicationFactor: Int)

  /**
   * Send a function 'Function0[Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function0[Any], replicationFactor: Int): List[Future[Any]]

  /**
   * Send a function 'Function1[Any, Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   */
  def send(f: Function1[Any, Unit], arg: Any, replicationFactor: Int)

  /**
   * Send a function 'Function1[Any, Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function1[Any, Any], arg: Any, replicationFactor: Int): List[Future[Any]]

  def setConfigElement(key: String, bytes: Array[Byte])

  /**
   * Returns the config element for the key or NULL if no element exists under the key.
   */
  def getConfigElement(key: String): Option[Array[Byte]]

  def removeConfigElement(key: String)

  def getConfigElementKeys: Array[String]

  private[cluster] def initializeNode()

  private[cluster] def addressForNode(node: String): Option[InetSocketAddress]

  private[cluster] def publish(change: ChangeNotification)

  private[cluster] def findFailedNodes(nodes: List[String]): List[String]

  private[cluster] def findNewlyConnectedMembershipNodes(nodes: List[String]): List[String]

  private[cluster] def findNewlyDisconnectedMembershipNodes(nodes: List[String]): List[String]

  private[cluster] def findNewlyConnectedAvailableNodes(nodes: List[String]): List[String]

  private[cluster] def findNewlyDisconnectedAvailableNodes(nodes: List[String]): List[String]

  private[cluster] def joinMembershipNode()

  private[cluster] def joinActorsAtAddressNode()

  private[cluster] def joinLeaderElection: Boolean

  private[cluster] def failOverConnections(from: InetSocketAddress, to: InetSocketAddress)

  private[cluster] def migrateFromFailedNodes[T <: Actor](currentSetOfClusterNodes: List[String])

  private[cluster] def membershipPathFor(node: String): String

  private[cluster] def configurationPathFor(key: String): String

  private[cluster] def actorAddressToUuidsPathFor(actorAddress: String): String

  private[cluster] def actorLocationsPathFor(uuid: UUID): String

  private[cluster] def actorLocationsPathFor(uuid: UUID, node: NodeAddress): String

  private[cluster] def actorsAtNodePathFor(node: String): String

  private[cluster] def actorAtNodePathFor(node: String, uuid: UUID): String

  private[cluster] def actorRegistryPathFor(uuid: UUID): String

  private[cluster] def actorRegistryFormatPathFor(uuid: UUID): String

  private[cluster] def actorRegistryActorAddressPathFor(uuid: UUID): String

  private[cluster] def actorRegistryNodePathFor(uuid: UUID): String

  private[cluster] def actorRegistryNodePathFor(uuid: UUID, address: InetSocketAddress): String
}

