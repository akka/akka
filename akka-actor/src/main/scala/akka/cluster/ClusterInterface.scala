/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor._
import DeploymentConfig._
import akka.dispatch.Future
import akka.routing._
import akka.serialization.Serializer
import akka.cluster.metrics._
import akka.util.Duration
import akka.util.duration._
import akka.{ AkkaException, AkkaApplication }

import com.eaio.uuid.UUID

import java.net.InetSocketAddress
import java.util.concurrent.{ ConcurrentSkipListSet }

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
class NodeAddress(val clusterName: String, val nodeName: String) {
  if ((clusterName eq null) || clusterName == "") throw new NullPointerException("Cluster name must not be null or empty string")
  if ((nodeName eq null) || nodeName == "") throw new NullPointerException("Node name must not be null or empty string")

  override def toString = "%s:%s".format(clusterName, nodeName)

  override def hashCode = 0 + clusterName.## + nodeName.##

  override def equals(other: Any) = NodeAddress.unapply(this) == NodeAddress.unapply(other)
}

/**
 * NodeAddress companion object and factory.
 */
object NodeAddress {
  def apply(clusterName: String, nodeName: String): NodeAddress = new NodeAddress(clusterName, nodeName)
  def apply(application: AkkaApplication): NodeAddress = new NodeAddress(application.AkkaConfig.ClusterName, application.nodename)

  def unapply(other: Any) = other match {
    case address: NodeAddress ⇒ Some((address.clusterName, address.nodeName))
    case _                    ⇒ None
  }
}

/*
 * Allows user to access metrics of a different nodes in the cluster. Changing metrics can be monitored
 * using {@link MetricsAlterationMonitor}
 * Metrics of the cluster nodes are distributed through ZooKeeper. For better performance, metrics are
 * cached internally, and refreshed from ZooKeeper after an interval
 */
trait NodeMetricsManager {

  /*
     * Gets metrics of a local node directly from JMX monitoring beans/Hyperic Sigar
     */
  def getLocalMetrics: NodeMetrics

  /*
     * Gets metrics of a specified node
     * @param nodeName metrics of the node specified by the name will be returned
     * @param useCached if <code>true</code>, returns metrics cached in the metrics manager,
     * gets metrics directly from ZooKeeper otherwise
     */
  def getMetrics(nodeName: String, useCached: Boolean = true): Option[NodeMetrics]

  /*
     * Gets cached metrics of all nodes in the cluster
     */
  def getAllMetrics: Array[NodeMetrics]

  /*
     * Adds monitor that reacts, when specific conditions are satisfied
     */
  def addMonitor(monitor: MetricsAlterationMonitor): Unit

  /*
     * Removes monitor
     */
  def removeMonitor(monitor: MetricsAlterationMonitor): Unit

  /*
     * Removes metrics of s specified node from ZooKeeper and metrics manager cache
     */
  def removeNodeMetrics(nodeName: String): Unit

  /*
     * Sets timeout after which metrics, cached in the metrics manager, will be refreshed from ZooKeeper
     */
  def refreshTimeout_=(newValue: Duration): Unit

  /*
     * Timeout after which metrics, cached in the metrics manager, will be refreshed from ZooKeeper
     */
  def refreshTimeout: Duration

  /*
     * Starts metrics manager. When metrics manager is started, it refreshes cache from ZooKeeper
     * after <code>refreshTimeout</code>, and invokes plugged monitors
     */
  def start(): NodeMetricsManager

  /*
     * Stops metrics manager. Stopped metrics manager doesn't refresh cache from ZooKeeper,
     * and doesn't invoke plugged monitors
     */
  def stop(): Unit

  /*
     * If the value is <code>true</code>, metrics manages is started and running. Stopped, otherwise
     */
  def isRunning: Boolean

}

/**
 * Interface for cluster node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ClusterNode {
  import ChangeListener._

  private[cluster] val locallyCachedMembershipNodes = new ConcurrentSkipListSet[String]()

  def membershipNodes: Array[String]

  def nodeAddress: NodeAddress

  def zkServerAddresses: String

  def start()

  def shutdown()

  def isShutdown: Boolean

  def disconnect(): ClusterNode

  def reconnect(): ClusterNode

  def metricsManager: NodeMetricsManager

  /**
   * Registers a cluster change listener.
   */
  def register(listener: ChangeListener): ClusterNode

  /**
   * Returns the name of the current leader.
   */
  def leader: String

  /**
   * Returns true if 'this' node is the current leader.
   */
  def isLeader: Boolean

  /**
   * Explicitly resign from being a leader. If this node is not a leader then this operation is a no-op.
   */
  def resign()

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], serializer: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], nrOfInstances: Int, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], nrOfInstances: Int, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], serializeMailbox: Boolean, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](actorAddress: String, actorClass: Class[T], nrOfInstances: Int, serializeMailbox: Boolean, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], nrOfInstances: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, serializeMailbox: Boolean, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, nrOfInstances: Int, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, nrOfInstances: Int, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, nrOfInstances: Int, serializeMailbox: Boolean, serializer: AnyRef): ClusterNode

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, nrOfInstances: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: AnyRef): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, nrOfInstances: Int, serializeMailbox: Boolean, serializer: Serializer): ClusterNode

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorAddress: String, actorFactory: () ⇒ ActorRef, nrOfInstances: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode

  /**
   * Removes actor from the cluster.
   */
  //  def remove(actorRef: ActorRef)

  /**
   * Removes actor with address from the cluster.
   */
  //  def remove(address: String): ClusterNode

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
  def isInUseOnNode(actorAddress: String, nodeName: String): Boolean

  /**
   * Is the actor with uuid in use or not?
   */
  def isInUseOnNode(actorAddress: String, node: NodeAddress): Boolean

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String): Option[LocalActorRef]

  /**
   * Using (checking out) actor on a specific set of nodes.
   */
  def useActorOnNodes(nodes: Array[String], actorAddress: String, replicateFromUuid: Option[UUID])

  /**
   * Using (checking out) actor on all nodes in the cluster.
   */
  def useActorOnAllNodes(actorAddress: String, replicateFromUuid: Option[UUID])

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(node: String, actorAddress: String, replicateFromUuid: Option[UUID])

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorRef: ActorRef)

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorAddress: String)

  /**
   * Creates an ActorRef with a Router to a set of clustered actors.
   */
  def ref(actorAddress: String, router: RouterType, failureDetector: FailureDetectorType): ActorRef

  /**
   * Returns the addresses of all actors checked out on this node.
   */
  def addressesForActorsInUse: Array[String]

  /**
   * Returns the addresses of all actors registered in this cluster.
   */
  def addressesForClusteredActors: Array[String]

  /**
   * Returns the addresses of all actors in use registered on a specific node.
   */
  def addressesForActorsInUseOnNode(nodeName: String): Array[String]

  /**
   * Returns Serializer for actor with UUID.
   */
  def serializerForActor(actorAddress: String): Serializer

  /**
   * Returns home address for actor with UUID.
   */
  def inetSocketAddressesForActor(actorAddress: String): Array[(UUID, InetSocketAddress)]

  /**
   * Send a function 'Function0[Unit]' to be invoked on a random number of nodes (defined by 'nrOfInstances' argument).
   */
  def send(f: Function0[Unit], nrOfInstances: Int)

  /**
   * Send a function 'Function0[Any]' to be invoked on a random number of nodes (defined by 'nrOfInstances' argument).
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function0[Any], nrOfInstances: Int): List[Future[Any]]

  /**
   * Send a function 'Function1[Any, Unit]' to be invoked on a random number of nodes (defined by 'nrOfInstances' argument)
   * with the argument speficied.
   */
  def send(f: Function1[Any, Unit], arg: Any, nrOfInstances: Int)

  /**
   * Send a function 'Function1[Any, Any]' to be invoked on a random number of nodes (defined by 'nrOfInstances' argument)
   * with the argument speficied.
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function1[Any, Any], arg: Any, nrOfInstances: Int): List[Future[Any]]

  /**
   * Stores a configuration element under a specific key.
   * If the key already exists then it will be overwritten.
   */
  def setConfigElement(key: String, bytes: Array[Byte])

  /**
   * Returns the config element for the key or NULL if no element exists under the key.
   * Returns <code>Some(element)</code> if it exists else <code>None</code>
   */
  def getConfigElement(key: String): Option[Array[Byte]]

  /**
   * Removes configuration element for a specific key.
   * Does nothing if the key does not exist.
   */
  def removeConfigElement(key: String)

  /**
   * Returns a list with all config element keys.
   */
  def getConfigElementKeys: Array[String]

  // =============== PRIVATE METHODS ===============

  // FIXME BAD BAD BAD - considering moving all these private[cluster] methods to a separate trait to get them out of the user's view

  private[cluster] def remoteClientLifeCycleHandler: ActorRef

  private[cluster] def remoteDaemon: ActorRef

  /**
   * Removes actor with uuid from the cluster.
   */
  //  private[cluster] def remove(uuid: UUID)

  /**
   * Releases (checking in) all actors with a specific UUID on all nodes in the cluster where the actor is in 'use'.
   */
  private[cluster] def releaseActorOnAllNodes(actorAddress: String)

  /**
   * Returns the UUIDs of all actors checked out on this node.
   */
  private[cluster] def uuidsForActorsInUse: Array[UUID]

  /**
   * Returns the UUIDs of all actors registered in this cluster.
   */
  private[cluster] def uuidsForClusteredActors: Array[UUID]

  /**
   * Returns the actor id for the actor with a specific UUID.
   */
  private[cluster] def actorAddressForUuid(uuid: UUID): Option[String]

  /**
   * Returns the actor ids for all the actors with a specific UUID.
   */
  private[cluster] def actorAddressForUuids(uuids: Array[UUID]): Array[String]

  /**
   * Returns the actor UUIDs for actor ID.
   */
  private[cluster] def uuidsForActorAddress(actorAddress: String): Array[UUID]

  /**
   * Returns the UUIDs of all actors in use registered on a specific node.
   */
  private[cluster] def uuidsForActorsInUseOnNode(nodeName: String): Array[UUID]

  private[cluster] def boot()

  private[cluster] def publish(change: ChangeNotification)

  private[cluster] def joinCluster()

  private[cluster] def joinLeaderElection: Boolean

  private[cluster] def failOverClusterActorRefConnections(from: InetSocketAddress, to: InetSocketAddress)

  private[cluster] def migrateActorsOnFailedNodes(
    failedNodes: List[String],
    currentClusterNodes: List[String],
    oldClusterNodes: List[String],
    disconnectedConnections: Map[String, InetSocketAddress])

  private[cluster] def connectToAllNewlyArrivedMembershipNodesInCluster(
    newlyConnectedMembershipNodes: Traversable[String],
    newlyDisconnectedMembershipNodes: Traversable[String]): Map[String, InetSocketAddress]

  private[cluster] def remoteSocketAddressForNode(node: String): Option[InetSocketAddress]

  private[cluster] def membershipPathFor(node: String): String
  private[cluster] def configurationPathFor(key: String): String

  private[cluster] def actorAddressToNodesPathFor(actorAddress: String): String
  private[cluster] def actorAddressToNodesPathFor(actorAddress: String, nodeName: String): String

  private[cluster] def nodeToUuidsPathFor(node: String): String
  private[cluster] def nodeToUuidsPathFor(node: String, uuid: UUID): String

  private[cluster] def actorAddressRegistryPathFor(actorAddress: String): String
  private[cluster] def actorAddressRegistrySerializerPathFor(actorAddress: String): String
  private[cluster] def actorAddressRegistryUuidPathFor(actorAddress: String): String

  private[cluster] def actorUuidRegistryPathFor(uuid: UUID): String
  private[cluster] def actorUuidRegistryNodePathFor(uuid: UUID): String
  private[cluster] def actorUuidRegistryAddressPathFor(uuid: UUID): String

  private[cluster] def actorAddressToUuidsPathFor(actorAddress: String): String
}

