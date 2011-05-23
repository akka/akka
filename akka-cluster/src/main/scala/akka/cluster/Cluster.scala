/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import org.apache.zookeeper._
import org.apache.zookeeper.Watcher.Event._
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.recipes.lock.{ WriteLock, LockListener }

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.serialize._
import org.I0Itec.zkclient.exception._

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference, AtomicInteger }
import java.util.concurrent.{ ConcurrentSkipListSet, CopyOnWriteArrayList, Callable, ConcurrentHashMap }
import java.net.InetSocketAddress
import javax.management.StandardMBean

import scala.collection.immutable.{ HashMap, HashSet }
import scala.collection.mutable.ConcurrentMap
import scala.collection.JavaConversions._

import ClusterProtocol._
import RemoteDaemonMessageType._

import akka.util._
import Helpers._
import akka.actor._
import Actor._
import akka.event.EventHandler
import akka.dispatch.{ Dispatchers, Future }
import akka.remoteinterface._
import akka.config.Config
import Config._
import akka.serialization.{ Format, Serializers, Serializer, Compression }
import Compression.LZF
import akka.AkkaException

import akka.cluster.zookeeper._

import com.eaio.uuid.UUID

import com.google.protobuf.ByteString
import java.util.{ List ⇒ JList }

// FIXME add watch for each node that when the entry for the node is removed then the node shuts itself down
// FIXME Provisioning data in ZK (file names etc) and files in S3 and on disk

class ClusterException(message: String) extends AkkaException(message)

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
    clusterName: String = Cluster.name,
    nodeName: String = Config.nodename,
    hostname: String = Config.hostname,
    port: Int = Config.remoteServerPort): NodeAddress =
    new NodeAddress(clusterName, nodeName, hostname, port)

  def unapply(other: Any) = other match {
    case address: NodeAddress ⇒ Some((address.clusterName, address.nodeName, address.hostname, address.port))
    case _                    ⇒ None
  }
}

/**
 * JMX MBean for the cluster service.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ClusterNodeMBean {
  def start()

  def stop()

  def disconnect()

  def reconnect()

  def resign()

  def isConnected: Boolean

  def getRemoteServerHostname: String

  def getRemoteServerPort: Int

  def getNodeName: String

  def getClusterName: String

  def getZooKeeperServerAddresses: String

  def getMemberNodes: Array[String]

  def getLeader: String

  def getUuidsForClusteredActors: Array[String]

  def getAddressesForClusteredActors: Array[String]

  def getUuidsForActorsInUse: Array[String]

  def getAddressesForActorsInUse: Array[String]

  def getNodesForActorInUseWithUuid(uuid: String): Array[String]

  def getNodesForActorInUseWithAddress(address: String): Array[String]

  def getUuidsForActorsInUseOnNode(nodeName: String): Array[String]

  def getAddressesForActorsInUseOnNode(nodeName: String): Array[String]

  def setConfigElement(key: String, value: String)

  def getConfigElement(key: String): AnyRef

  def removeConfigElement(key: String)

  def getConfigElementKeys: Array[String]
}

/**
 * Module for the ClusterNode. Also holds global state such as configuration data etc.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Cluster {
  val EMPTY_STRING = "".intern
  val UUID_PREFIX = "uuid:".intern

  // config options
  val name = config.getString("akka.cluster.name", "default")
  val zooKeeperServers = config.getString("akka.cluster.zookeeper-server-addresses", "localhost:2181")
  val remoteServerPort = config.getInt("akka.cluster.remote-server-port", 2552)
  val sessionTimeout = Duration(config.getInt("akka.cluster.session-timeout", 60), TIME_UNIT).toMillis.toInt
  val connectionTimeout = Duration(config.getInt("akka.cluster.connection-timeout", 60), TIME_UNIT).toMillis.toInt
  val maxTimeToWaitUntilConnected = Duration(config.getInt("akka.cluster.max-time-to-wait-until-connected", 30), TIME_UNIT).toMillis.toInt
  val shouldCompressData = config.getBool("akka.cluster.use-compression", false)
  val enableJMX = config.getBool("akka.enable-jmx", true)

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

  @volatile
  private var properties = Map.empty[String, String]

  def setProperty(property: (String, String)) {
    properties = properties + property
  }

  private def nodename: String = properties.get("akka.cluster.nodename") match {
    case Some(uberride) ⇒ uberride
    case None           ⇒ Config.nodename
  }

  private def hostname: String = properties.get("akka.cluster.hostname") match {
    case Some(uberride) ⇒ uberride
    case None           ⇒ Config.hostname
  }

  private def port: Int = properties.get("akka.cluster.port") match {
    case Some(uberride) ⇒ uberride.toInt
    case None           ⇒ Config.remoteServerPort
  }

  val defaultSerializer = new SerializableSerializer

  private val _zkServer = new AtomicReference[Option[ZkServer]](None)

  /**
   * The node address.
   */
  lazy val nodeAddress = NodeAddress(name, nodename, hostname, port)

  /**
   * The reference to the running ClusterNode.
   */
  lazy val node: ClusterNode = {
    if (nodeAddress eq null) throw new IllegalArgumentException("NodeAddress can't be null")
    new ClusterNode(nodeAddress, zooKeeperServers, defaultSerializer)
  }

  /**
   * Looks up the local hostname.
   */
  def lookupLocalhostName = NetworkUtil.getLocalhostName

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(): ZkServer =
    startLocalCluster("_akka_cluster/data", "_akka_cluster/log", 2181, 5000)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(port: Int, tickTime: Int): ZkServer =
    startLocalCluster("_akka_cluster/data", "_akka_cluster/log", port, tickTime)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(tickTime: Int): ZkServer =
    startLocalCluster("_akka_cluster/data", "_akka_cluster/log", 2181, tickTime)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(dataPath: String, logPath: String): ZkServer =
    startLocalCluster(dataPath, logPath, 2181, 500)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(dataPath: String, logPath: String, port: Int, tickTime: Int): ZkServer = {
    try {
      val zkServer = AkkaZooKeeper.startLocalServer(dataPath, logPath, port, tickTime)
      _zkServer.set(Some(zkServer))
      zkServer
    } catch {
      case e: Throwable ⇒
        EventHandler.error(e, this, "Could not start local ZooKeeper cluster")
        throw e
    }
  }

  /**
   * Shut down the local ZooKeeper server.
   */
  def shutdownLocalCluster() {
    withPrintStackTraceOnError {
      EventHandler.info(this, "Shuts down local cluster")
      _zkServer.get.foreach(_.shutdown())
      _zkServer.set(None)
    }
  }

  /**
   * Creates a new AkkaZkClient.
   */
  def newZkClient: AkkaZkClient = new AkkaZkClient(zooKeeperServers, sessionTimeout, connectionTimeout, defaultSerializer)

  def uuidToString(uuid: UUID): String = uuid.toString

  def stringToUuid(uuid: String): UUID = {
    if (uuid eq null) throw new ClusterException("UUID is null")
    if (uuid == "") throw new ClusterException("UUID is an empty string")
    try {
      new UUID(uuid)
    } catch {
      case e: StringIndexOutOfBoundsException ⇒
        val error = new ClusterException("UUID not valid [" + uuid + "]")
        EventHandler.error(error, this, "")
        throw error
    }
  }

  def uuidProtocolToUuid(uuid: UuidProtocol) = new UUID(uuid.getHigh, uuid.getLow)

  def uuidToUuidProtocol(uuid: UUID) =
    UuidProtocol.newBuilder
      .setHigh(uuid.getTime)
      .setLow(uuid.getClockSeqAndNode)
      .build
}

/**
 * A Cluster is made up by a bunch of jvm's, the ClusterNode.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ClusterNode private[akka] (
  val nodeAddress: NodeAddress,
  val zkServerAddresses: String,
  val serializer: ZkSerializer) extends ErrorHandler {
  self ⇒

  if (nodeAddress eq null) throw new IllegalArgumentException("'nodeAddress' can not be 'null'")

  import Cluster._

  EventHandler.info(this,
    ("\nCreating cluster node with" +
      "\n\tcluster name = [%s]" +
      "\n\tnode name = [%s]" +
      "\n\tport = [%s]" +
      "\n\tzookeeper server addresses = [%s]" +
      "\n\tserializer = [%s]")
      .format(nodeAddress.clusterName, nodeAddress.nodeName, nodeAddress.port, zkServerAddresses, serializer))

  val remoteClientLifeCycleListener = actorOf(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) ⇒ client.shutdownClientModule()
      case RemoteClientDisconnected(client, address) ⇒ client.shutdownClientModule()
      case _                                         ⇒ //ignore other
    }
  }, "akka.cluster.remoteClientLifeCycleListener").start()

  lazy val remoteDaemon = actorOf(new RemoteClusterDaemon(this), RemoteClusterDaemon.ADDRESS).start()

  lazy val remoteService: RemoteSupport = {
    val remote = new akka.remote.netty.NettyRemoteSupport
    remote.start(nodeAddress.hostname, nodeAddress.port)
    remote.register(RemoteClusterDaemon.ADDRESS, remoteDaemon)
    remote.addListener(remoteClientLifeCycleListener)
    remote
  }
  lazy val remoteServerAddress: InetSocketAddress = remoteService.address

  val clusterJmxObjectName = JMX.nameFor(nodeAddress.hostname, "monitoring", "cluster")

  // static nodes
  val CLUSTER_NODE = "/" + nodeAddress.clusterName
  val MEMBERSHIP_NODE = CLUSTER_NODE + "/members"
  val CONFIGURATION_NODE = CLUSTER_NODE + "/config"
  val PROVISIONING_NODE = CLUSTER_NODE + "/provisioning"
  val ACTOR_REGISTRY_NODE = CLUSTER_NODE + "/actor-registry"
  val ACTOR_LOCATIONS_NODE = CLUSTER_NODE + "/actor-locations"
  val ACTOR_ADDRESS_TO_UUIDS_NODE = CLUSTER_NODE + "/actor-address-to-uuids"
  val ACTORS_AT_NODE_NODE = CLUSTER_NODE + "/actors-at-address"
  val baseNodes = List(
    CLUSTER_NODE,
    MEMBERSHIP_NODE,
    ACTOR_REGISTRY_NODE,
    ACTOR_LOCATIONS_NODE,
    ACTORS_AT_NODE_NODE,
    ACTOR_ADDRESS_TO_UUIDS_NODE,
    CONFIGURATION_NODE,
    PROVISIONING_NODE)

  val LEADER_ELECTION_NODE = CLUSTER_NODE + "/leader" // should NOT be part of 'baseNodes' only used by 'leaderLock'

  val isConnected = new Switch(false)
  val isLeader = new AtomicBoolean(false)
  val electionNumber = new AtomicInteger(Integer.MAX_VALUE)

  private val membershipNodePath = membershipPathFor(nodeAddress.nodeName)

  // local caches of ZK data
  private[akka] val locallyCachedMembershipNodes = new ConcurrentSkipListSet[String]()
  private[akka] val nodeNameToAddress: ConcurrentMap[String, InetSocketAddress] = new ConcurrentHashMap[String, InetSocketAddress]
  private[akka] val locallyCheckedOutActors: ConcurrentMap[UUID, Array[Byte]] = new ConcurrentHashMap[UUID, Array[Byte]]

  def membershipNodes: Array[String] = locallyCachedMembershipNodes.toList.toArray.asInstanceOf[Array[String]]

  private[akka] val replicaConnections: ConcurrentMap[String, Tuple2[InetSocketAddress, ActorRef]] =
    new ConcurrentHashMap[String, Tuple2[InetSocketAddress, ActorRef]]

  // zookeeper listeners
  private val stateListener = new StateListener(this)
  private val membershipListener = new MembershipChildListener(this)

  // cluster node listeners
  private val changeListeners = new CopyOnWriteArrayList[ChangeListener]()

  // Address -> ClusterActorRef
  private val clusterActorRefs = new Index[InetSocketAddress, ClusterActorRef]

  // resources
  private[cluster] val zkClient = new AkkaZkClient(zkServerAddresses, sessionTimeout, connectionTimeout, serializer)

  private[cluster] val leaderElectionCallback = new LockListener {
    override def lockAcquired() {
      EventHandler.info(this, "Node [%s] is the new leader".format(self.nodeAddress.nodeName))
      self.isLeader.set(true)
      self.publish(Cluster.NewLeader(self.nodeAddress.nodeName))
    }

    override def lockReleased() {
      EventHandler.info(this,
        "Node [%s] is *NOT* the leader anymore".format(self.nodeAddress.nodeName))
      self.isLeader.set(false)
      // self.publish(Cluster.LeaderChange)
    }
  }

  private[cluster] val leaderLock = new WriteLock(
    zkClient.connection.getZookeeper, LEADER_ELECTION_NODE, null, leaderElectionCallback) {
    // ugly hack, but what do you do? <--- haha epic
    private val ownerIdField = classOf[WriteLock].getDeclaredField("ownerId")
    ownerIdField.setAccessible(true)

    def leader: String = ownerIdField.get(this).asInstanceOf[String]
  }

  if (enableJMX) createMBean

  // =======================================
  // Node
  // =======================================

  def isRunning: Boolean = isConnected.isOn

  def start(): ClusterNode = {
    isConnected switchOn {
      initializeNode()
    }
    this
  }

  def shutdown() {
    isConnected switchOff {
      ignore[ZkNoNodeException](zkClient.deleteRecursive(membershipNodePath))

      locallyCachedMembershipNodes.clear()
      locallyCheckedOutActors.clear()

      replicaConnections.toList.foreach({
        case (_, (address, _)) ⇒
          Actor.remote.shutdownClientConnection(address) // shut down client connections
      })

      remoteService.shutdown() // shutdown server

      remoteClientLifeCycleListener.stop()
      remoteDaemon.stop()

      // for monitoring remote listener
      registry.local.actors.filter(remoteService.hasListener).foreach(_.stop())

      replicaConnections.clear()

      disconnect()
      EventHandler.info(this, "Cluster node shut down [%s]".format(nodeAddress))
    }
  }

  def disconnect(): ClusterNode = {
    zkClient.unsubscribeAll()
    zkClient.close()
    this
  }

  def reconnect(): ClusterNode = {
    zkClient.reconnect()
    this
  }

  // =======================================
  // Change notification
  // =======================================

  /**
   * Registers a cluster change listener.
   */
  def register(listener: ChangeListener): ClusterNode = if (isConnected.isOff) {
    changeListeners.add(listener)
    this
  } else throw new IllegalStateException("Can not register 'ChangeListener' after the cluster node has been started")

  private[cluster] def publish(change: ChangeNotification) {
    changeListeners.iterator.foreach(_.notify(change, this))
  }

  // =======================================
  // Leader
  // =======================================

  /**
   * Returns the name of the current leader.
   */
  def leader: String = leaderLock.leader

  /**
   * Explicitly resign from being a leader. If this node is not a leader then this operation is a no-op.
   */
  def resign() {
    if (isLeader.get) leaderLock.unlock()
  }

  // =======================================
  // Actor
  // =======================================

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], format: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, 0, false, format)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, format: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, replicationFactor, false, format)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], serializeMailbox: Boolean, format: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, 0, serializeMailbox, format)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, serializeMailbox: Boolean, format: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, replicationFactor, serializeMailbox, format)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, format: Serializer): ClusterNode =
    store(actorRef, 0, false, format)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, format: Serializer): ClusterNode =
    store(actorRef, replicationFactor, false, format)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, serializeMailbox: Boolean, format: Serializer): ClusterNode =
    store(actorRef, 0, serializeMailbox, format)

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, serializeMailbox: Boolean, format: AnyRef): ClusterNode =
    store(actorRef, replicationFactor, serializeMailbox, format.asInstanceOf[Serializer])

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, serializeMailbox: Boolean, format: Serializer): ClusterNode = if (isConnected.isOn) {

    import akka.serialization.ActorSerialization._

    if (!actorRef.isInstanceOf[LocalActorRef]) throw new IllegalArgumentException(
      "'actorRef' must be an instance of 'LocalActorRef' [" + actorRef.getClass.getName + "]")

    val uuid = actorRef.uuid
    EventHandler.debug(this,
      "Clustering actor [%s] with UUID [%s]".format(actorRef.address, uuid))

    val actorBytes = if (shouldCompressData) LZF.compress(toBinary(actorRef, serializeMailbox)(format))
    else toBinary(actorRef)(format)
    val actorRegistryPath = actorRegistryPathFor(uuid)

    // create UUID -> Array[Byte] for actor registry
    if (zkClient.exists(actorRegistryPath)) zkClient.writeData(actorRegistryPath, actorBytes) // FIXME check for size and warn if too big
    else {
      zkClient.retryUntilConnected(new Callable[Either[String, Exception]]() {
        def call: Either[String, Exception] = {
          try {
            Left(zkClient.connection.create(actorRegistryPath, actorBytes, CreateMode.PERSISTENT))
          } catch {
            case e: KeeperException.NodeExistsException ⇒ Right(e)
          }
        }
      }) match {
        case Left(path)       ⇒ path
        case Right(exception) ⇒ actorRegistryPath
      }

      // create UUID -> Format registry
      try {
        zkClient.createPersistent(actorRegistryFormatPathFor(uuid), format)
      } catch {
        case e: ZkNodeExistsException ⇒ zkClient.writeData(actorRegistryFormatPathFor(uuid), format)
      }

      // create UUID -> ADDRESS registry
      try {
        zkClient.createPersistent(actorRegistryActorAddressPathFor(uuid), actorRef.address)
      } catch {
        case e: ZkNodeExistsException ⇒ zkClient.writeData(actorRegistryActorAddressPathFor(uuid), actorRef.address)
      }

      // create UUID -> Address registry
      ignore[ZkNodeExistsException](zkClient.createPersistent(actorRegistryNodePathFor(uuid)))

      // create UUID -> Node registry
      ignore[ZkNodeExistsException](zkClient.createPersistent(actorLocationsPathFor(uuid)))

      // create ADDRESS -> UUIDs registry
      ignore[ZkNodeExistsException](zkClient.createPersistent(actorAddressToUuidsPathFor(actorRef.address)))
      ignore[ZkNodeExistsException](zkClient.createPersistent("%s/%s".format(actorAddressToUuidsPathFor(actorRef.address), uuid)))
    }

    val command = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorUuid(uuidToUuidProtocol(uuid))
      .build
    replicaConnectionsForReplicationFactor(replicationFactor) foreach { connection ⇒
      connection ! command
    }

    this
  } else throw new ClusterException("Not connected to cluster")

  /**
   * Removes actor with uuid from the cluster.
   */
  def remove(uuid: UUID) {
    releaseActorOnAllNodes(uuid)

    locallyCheckedOutActors.remove(uuid)
    // warning: ordering matters here
    ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAddressToUuidsPathFor(actorAddressForUuid(uuid)))) // remove ADDRESS to UUID mapping
    ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAtNodePathFor(nodeAddress.nodeName, uuid)))
    ignore[ZkNoNodeException](zkClient.deleteRecursive(actorRegistryPathFor(uuid)))
    ignore[ZkNoNodeException](zkClient.deleteRecursive(actorLocationsPathFor(uuid)))
  }

  /**
   * Removes actor with address from the cluster.
   */
  def remove(address: String): ClusterNode = {
    isConnected ifOn {
      EventHandler.debug(this,
        "Removing actor(s) with ADDRESS [%s] from cluster".format(address))
      uuidsForActorAddress(address) foreach (uuid ⇒ remove(uuid))
    }
    this
  }

  /**
   * Is the actor with uuid clustered or not?
   */
  def isClustered(actorAddress: String): Boolean = if (isConnected.isOn) {
    actorUuidsForActorAddress(actorAddress) map { uuid ⇒
      zkClient.exists(actorRegistryPathFor(uuid))
    } exists (_ == true)
  } else false

  /**
   * Is the actor with uuid in use on 'this' node or not?
   */
  def isInUseOnNode(actorAddress: String): Boolean = isInUseOnNode(actorAddress, nodeAddress)

  /**
   * Is the actor with uuid in use or not?
   */
  def isInUseOnNode(actorAddress: String, node: NodeAddress): Boolean = if (isConnected.isOn) {
    actorUuidsForActorAddress(actorAddress) map { uuid ⇒
      zkClient.exists(actorLocationsPathFor(uuid, node))
    } exists (_ == true)
  } else false

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String): Array[LocalActorRef] = use(actorAddress, formatForActor(actorAddress))

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String, format: Serializer): Array[LocalActorRef] = if (isConnected.isOn) {

    import akka.serialization.ActorSerialization._

    actorUuidsForActorAddress(actorAddress) map { uuid ⇒
      EventHandler.debug(this,
        "Checking out actor with UUID [%s] to be used on node [%s]".format(uuid, nodeAddress.nodeName))

      ignore[ZkNodeExistsException](zkClient.createPersistent(actorAtNodePathFor(nodeAddress.nodeName, uuid), true))
      ignore[ZkNodeExistsException](zkClient.createEphemeral(actorLocationsPathFor(uuid, nodeAddress)))

      // set home address
      ignore[ZkNodeExistsException](zkClient.createPersistent(actorRegistryNodePathFor(uuid)))
      ignore[ZkNodeExistsException](zkClient.createEphemeral(actorRegistryNodePathFor(uuid, remoteServerAddress)))

      val actorPath = actorRegistryPathFor(uuid)
      zkClient.retryUntilConnected(new Callable[Either[Array[Byte], Exception]]() {
        def call: Either[Array[Byte], Exception] = {
          try {
            Left(if (shouldCompressData) LZF.uncompress(zkClient.connection.readData(actorPath, new Stat, false))
            else zkClient.connection.readData(actorPath, new Stat, false))
          } catch {
            case e: KeeperException.NodeExistsException ⇒ Right(e)
          }
        }
      }) match {
        case Left(bytes) ⇒
          locallyCheckedOutActors += (uuid -> bytes)
          // FIXME switch to ReplicatedActorRef here
          // val actor = new ReplicatedActorRef(fromBinary[T](bytes, remoteServerAddress)(format))
          val actor = fromBinary[T](bytes, remoteServerAddress)(format)
          remoteService.register(UUID_PREFIX + uuid, actor) // clustered refs are always registered and looked up by UUID
          actor.start()
          actor.asInstanceOf[LocalActorRef]
        case Right(exception) ⇒ throw exception
      }
    }
  } else Array.empty[LocalActorRef]

  /**
   * Using (checking out) all actors with a specific UUID on all nodes in the cluster.
   */
  def useActorOnAllNodes(uuid: UUID) {
    isConnected ifOn {
      EventHandler.debug(this,
        "Using (checking out) all actors with UUID [%s] on all nodes in cluster".format(uuid))
      val command = RemoteDaemonMessageProtocol.newBuilder
        .setMessageType(USE)
        .setActorUuid(uuidToUuidProtocol(uuid))
        .build
      membershipNodes foreach { node ⇒
        replicaConnections.get(node) foreach {
          case (_, connection) ⇒
            connection ! command
        }
      }
    }
  }

  /**
   * Using (checking out) specific UUID on a specefic node.
   */
  def useActorOnNode(node: String, uuid: UUID) {
    isConnected ifOn {
      replicaConnections.get(node) foreach {
        case (_, connection) ⇒
          connection ! RemoteDaemonMessageProtocol.newBuilder
            .setMessageType(USE)
            .setActorUuid(uuidToUuidProtocol(uuid))
            .build
      }
    }
  }

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorAddress: String) {
    isConnected ifOn {
      actorUuidsForActorAddress(actorAddress) foreach { uuid ⇒
        EventHandler.debug(this,
          "Releasing actor with UUID [%s] after usage".format(uuid))
        locallyCheckedOutActors.remove(uuid)
        ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAtNodePathFor(nodeAddress.nodeName, uuid)))
        ignore[ZkNoNodeException](zkClient.delete(actorAtNodePathFor(nodeAddress.nodeName, uuid)))
        ignore[ZkNoNodeException](zkClient.delete(actorLocationsPathFor(uuid, nodeAddress)))
        ignore[ZkNoNodeException](zkClient.delete(actorRegistryNodePathFor(uuid, remoteServerAddress)))
      }
    }
  }

  /**
   * Releases (checking in) all actors with a specific UUID on all nodes in the cluster where the actor is in 'use'.
   */
  def releaseActorOnAllNodes(uuid: UUID) {
    isConnected ifOn {
      EventHandler.debug(this,
        "Releasing (checking in) all actors with UUID [%s] on all nodes in cluster".format(uuid))
      val command = RemoteDaemonMessageProtocol.newBuilder
        .setMessageType(RELEASE)
        .setActorUuid(uuidToUuidProtocol(uuid))
        .build
      nodesForActorsInUseWithUuid(uuid) foreach { node ⇒
        replicaConnections.get(node) foreach {
          case (_, connection) ⇒
            connection ! command
        }
      }
    }
  }

  /**
   * Creates an ActorRef with a Router to a set of clustered actors.
   */
  def ref(actorAddress: String, router: RouterType): ActorRef = if (isConnected.isOn) {

    val addresses = addressesForActor(actorAddress)

    EventHandler.debug(this,
      "Creating cluster actor ref with router [%s] for actors [%s]".format(router, addresses.mkString(", ")))

    def registerClusterActorRefForAddress(actorRef: ClusterActorRef, addresses: Array[(UUID, InetSocketAddress)]) {
      addresses foreach {
        case (_, address) ⇒ clusterActorRefs.put(address, actorRef)
      }
    }

    // FIXME remove?
    def refByUuid(uuid: UUID): ActorRef = {
      val actor = Router newRouter (router, addresses, uuidToString(uuid), Actor.TIMEOUT)
      registerClusterActorRefForAddress(actor, addresses)
      actor
    }

    def refByAddress(actorAddress: String): ActorRef = {
      //FIXME: unused uuids
      val uuids = uuidsForActorAddress(actorAddress)
      val actor = Router newRouter (router, addresses, actorAddress, Actor.TIMEOUT)
      registerClusterActorRefForAddress(actor, addresses)
      actor
    }

    refByAddress(actorAddress)
  } else throw new ClusterException("Not connected to cluster")

  /**
   * Migrate the actor from 'this' node to node 'to'.
   */
  def migrate(to: NodeAddress, actorAddress: String) {
    migrate(nodeAddress, to, actorAddress)
  }

  /**
   * Migrate the actor from node 'from' to node 'to'.
   */
  def migrate(
    from: NodeAddress, to: NodeAddress, actorAddress: String) {
    isConnected ifOn {
      if (from eq null) throw new IllegalArgumentException("NodeAddress 'from' can not be 'null'")
      if (to eq null) throw new IllegalArgumentException("NodeAddress 'to' can not be 'null'")
      if (isInUseOnNode(actorAddress, from)) {
        migrateWithoutCheckingThatActorResidesOnItsHomeNode(from, to, actorAddress)
      } else {
        throw new ClusterException("Can't move actor from node [" + from + "] since it does not exist on this node")
      }
    }
  }

  /**
   * Returns the UUIDs of all actors checked out on this node.
   */
  def uuidsForActorsInUse: Array[UUID] = uuidsForActorsInUseOnNode(nodeAddress.nodeName)

  /**
   * Returns the addresses of all actors checked out on this node.
   */
  def addressesForActorsInUse: Array[String] = actorAddressForUuids(uuidsForActorsInUse)

  /**
   * Returns the UUIDs of all actors registered in this cluster.
   */
  def uuidsForClusteredActors: Array[UUID] = if (isConnected.isOn) {
    zkClient.getChildren(ACTOR_REGISTRY_NODE).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]]
  } else Array.empty[UUID]

  /**
   * Returns the addresses of all actors registered in this cluster.
   */
  def addressesForClusteredActors: Array[String] = actorAddressForUuids(uuidsForClusteredActors)

  /**
   * Returns the actor id for the actor with a specific UUID.
   */
  def actorAddressForUuid(uuid: UUID): String = if (isConnected.isOn) {
    try {
      zkClient.readData(actorRegistryActorAddressPathFor(uuid)).asInstanceOf[String]
    } catch {
      case e: ZkNoNodeException ⇒ ""
    }
  } else ""

  /**
   * Returns the actor ids for all the actors with a specific UUID.
   */
  def actorAddressForUuids(uuids: Array[UUID]): Array[String] = uuids map (actorAddressForUuid(_)) filter (_ != "")

  /**
   * Returns the actor UUIDs for actor ID.
   */
  def uuidsForActorAddress(actorAddress: String): Array[UUID] = if (isConnected.isOn) {
    try {
      zkClient.getChildren(actorAddressToUuidsPathFor(actorAddress)).toArray map {
        case c: CharSequence ⇒ new UUID(c)
      }
    } catch {
      case e: ZkNoNodeException ⇒ Array[UUID]()
    }
  } else Array.empty[UUID]

  /**
   * Returns the node names of all actors in use with UUID.
   */
  def nodesForActorsInUseWithUuid(uuid: UUID): Array[String] = if (isConnected.isOn) {
    try {
      zkClient.getChildren(actorLocationsPathFor(uuid)).toArray.asInstanceOf[Array[String]]
    } catch {
      case e: ZkNoNodeException ⇒ Array[String]()
    }
  } else Array.empty[String]

  /**
   * Returns the node names of all actors in use with address.
   */
  def nodesForActorsInUseWithAddress(address: String): Array[String] = if (isConnected.isOn) {
    flatten {
      actorUuidsForActorAddress(address) map { uuid ⇒
        try {
          zkClient.getChildren(actorLocationsPathFor(uuid)).toArray.asInstanceOf[Array[String]]
        } catch {
          case e: ZkNoNodeException ⇒ Array[String]()
        }
      }
    }
  } else Array.empty[String]

  /**
   * Returns the UUIDs of all actors in use registered on a specific node.
   */
  def uuidsForActorsInUseOnNode(nodeName: String): Array[UUID] = if (isConnected.isOn) {
    try {
      zkClient.getChildren(actorsAtNodePathFor(nodeName)).toArray map {
        case c: CharSequence ⇒ new UUID(c)
      }
    } catch {
      case e: ZkNoNodeException ⇒ Array[UUID]()
    }
  } else Array.empty[UUID]

  /**
   * Returns the addresses of all actors in use registered on a specific node.
   */
  def addressesForActorsInUseOnNode(nodeName: String): Array[String] = if (isConnected.isOn) {
    val uuids =
      try {
        zkClient.getChildren(actorsAtNodePathFor(nodeName)).toArray map {
          case c: CharSequence ⇒ new UUID(c)
        }
      } catch {
        case e: ZkNoNodeException ⇒ Array[UUID]()
      }
    actorAddressForUuids(uuids)
  } else Array.empty[String]

  /**
   * Returns Format for actor with UUID.
   */
  def formatForActor(actorAddress: String): Serializer = {

    val formats = actorUuidsForActorAddress(actorAddress) map { uuid ⇒
      zkClient.readData(actorRegistryFormatPathFor(uuid), new Stat).asInstanceOf[Serializer]
    }

    if (formats.isEmpty) throw new IllegalStateException("No Serializer found for [%s]".format(actorAddress))
    if (formats.forall(_ == formats.head) == false) throw new IllegalStateException("Multiple Serializer classes found for [%s]".format(actorAddress))

    formats.head
  }

  /**
   * Returns home address for actor with UUID.
   */
  def addressesForActor(actorAddress: String): Array[(UUID, InetSocketAddress)] = {
    try {
      for {
        uuid ← actorUuidsForActorAddress(actorAddress)
        address ← zkClient.getChildren(actorRegistryNodePathFor(uuid)).toList
      } yield {
        val tokenizer = new java.util.StringTokenizer(address, ":")
        val hostname = tokenizer.nextToken // hostname
        val port = tokenizer.nextToken.toInt // port
        (uuid, new InetSocketAddress(hostname, port))
      }
    } catch {
      case e: ZkNoNodeException ⇒ Array[(UUID, InetSocketAddress)]()
    }
  }

  // =======================================
  // Compute Grid
  // =======================================

  /**
   * Send a function 'Function0[Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   */
  def send(f: Function0[Unit], replicationFactor: Int) {
    val message = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(FUNCTION_FUN0_UNIT)
      .setPayload(ByteString.copyFrom(Serializers.Java.toBinary(f)))
      .build
    replicaConnectionsForReplicationFactor(replicationFactor) foreach (_ ! message)
  }

  /**
   * Send a function 'Function0[Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function0[Any], replicationFactor: Int): List[Future[Any]] = {
    val message = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(FUNCTION_FUN0_ANY)
      .setPayload(ByteString.copyFrom(Serializers.Java.toBinary(f)))
      .build
    val results = replicaConnectionsForReplicationFactor(replicationFactor) map (_ !!! message)
    results.toList.asInstanceOf[List[Future[Any]]]
  }

  /**
   * Send a function 'Function1[Any, Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   */
  def send(f: Function1[Any, Unit], arg: Any, replicationFactor: Int) {
    val message = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(FUNCTION_FUN1_ARG_UNIT)
      .setPayload(ByteString.copyFrom(Serializers.Java.toBinary((f, arg))))
      .build
    replicaConnectionsForReplicationFactor(replicationFactor) foreach (_ ! message)
  }

  /**
   * Send a function 'Function1[Any, Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function1[Any, Any], arg: Any, replicationFactor: Int): List[Future[Any]] = {
    val message = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(FUNCTION_FUN1_ARG_ANY)
      .setPayload(ByteString.copyFrom(Serializers.Java.toBinary((f, arg))))
      .build
    val results = replicaConnectionsForReplicationFactor(replicationFactor) map (_ !!! message)
    results.toList.asInstanceOf[List[Future[Any]]]
  }

  // =======================================
  // Config
  // =======================================

  def setConfigElement(key: String, bytes: Array[Byte]) {
    val compressedBytes = if (shouldCompressData) LZF.compress(bytes) else bytes
    EventHandler.debug(this,
      "Adding config value [%s] under key [%s] in cluster registry".format(key, compressedBytes))
    zkClient.retryUntilConnected(new Callable[Either[Unit, Exception]]() {
      def call: Either[Unit, Exception] = {
        try {
          Left(zkClient.connection.create(configurationPathFor(key), compressedBytes, CreateMode.PERSISTENT))
        } catch {
          case e: KeeperException.NodeExistsException ⇒
            try {
              Left(zkClient.connection.writeData(configurationPathFor(key), compressedBytes))
            } catch {
              case e: Exception ⇒ Right(e)
            }
        }
      }
    }) match {
      case Left(_)          ⇒ /* do nothing */
      case Right(exception) ⇒ throw exception
    }
  }

  /**
   * Returns the config element for the key or NULL if no element exists under the key.
   */
  def getConfigElement(key: String): Array[Byte] = try {
    zkClient.connection.readData(configurationPathFor(key), new Stat, true)
  } catch {
    case e: KeeperException.NoNodeException ⇒ null
  }

  def removeConfigElement(key: String) {
    ignore[ZkNoNodeException] {
      EventHandler.debug(this,
        "Removing config element with key [%s] from cluster registry".format(key))
      zkClient.deleteRecursive(configurationPathFor(key))
    }
  }

  def getConfigElementKeys: Array[String] = zkClient.getChildren(CONFIGURATION_NODE).toList.toArray.asInstanceOf[Array[String]]

  // =======================================
  // Queue
  // =======================================

  def createQueue(rootPath: String, blocking: Boolean = true) = new ZooKeeperQueue(zkClient, rootPath, blocking)

  // =======================================
  // Barrier
  // =======================================

  def barrier(name: String, count: Int) =
    ZooKeeperBarrier(zkClient, nodeAddress.clusterName, name, nodeAddress.nodeName, count)

  def barrier(name: String, count: Int, timeout: Duration) =
    ZooKeeperBarrier(zkClient, nodeAddress.clusterName, name, nodeAddress.nodeName, count, timeout)

  // =======================================
  // Private
  // =======================================

  private[cluster] def membershipPathFor(node: String) = "%s/%s".format(MEMBERSHIP_NODE, node)

  private[cluster] def configurationPathFor(key: String) = "%s/%s".format(CONFIGURATION_NODE, key)

  private[cluster] def actorAddressToUuidsPathFor(actorAddress: String) = "%s/%s".format(ACTOR_ADDRESS_TO_UUIDS_NODE, actorAddress.replace('.', '_'))

  private[cluster] def actorLocationsPathFor(uuid: UUID) = "%s/%s".format(ACTOR_LOCATIONS_NODE, uuid)

  private[cluster] def actorLocationsPathFor(uuid: UUID, node: NodeAddress) =
    "%s/%s/%s".format(ACTOR_LOCATIONS_NODE, uuid, node.nodeName)

  private[cluster] def actorsAtNodePathFor(node: String) = "%s/%s".format(ACTORS_AT_NODE_NODE, node)

  private[cluster] def actorAtNodePathFor(node: String, uuid: UUID) = "%s/%s/%s".format(ACTORS_AT_NODE_NODE, node, uuid)

  private[cluster] def actorRegistryPathFor(uuid: UUID) = "%s/%s".format(ACTOR_REGISTRY_NODE, uuid)

  private[cluster] def actorRegistryFormatPathFor(uuid: UUID) = "%s/%s".format(actorRegistryPathFor(uuid), "format")

  private[cluster] def actorRegistryActorAddressPathFor(uuid: UUID) = "%s/%s".format(actorRegistryPathFor(uuid), "address")

  private[cluster] def actorRegistryNodePathFor(uuid: UUID): String = "%s/%s".format(actorRegistryPathFor(uuid), "node")

  private[cluster] def actorRegistryNodePathFor(uuid: UUID, address: InetSocketAddress): String =
    "%s/%s:%s".format(actorRegistryNodePathFor(uuid), address.getHostName, address.getPort)

  private[cluster] def initializeNode() {
    EventHandler.info(this, "Initializing cluster node [%s]".format(nodeAddress))
    EventHandler.info(this, "Starting up remote server [%s]".format(remoteServerAddress.toString))
    createRootClusterNode()
    val isLeader = joinLeaderElection
    if (isLeader) createNodeStructureIfNeeded()
    registerListeners
    joinMembershipNode()
    joinActorsAtAddressNode()
    fetchMembershipChildrenNodes()
    EventHandler.info(this, "Cluster node [%s] started successfully".format(nodeAddress))
  }

  private[cluster] def addressForNode(node: String): InetSocketAddress = {
    val address = zkClient.readData(membershipPathFor(node)).asInstanceOf[String]
    val tokenizer = new java.util.StringTokenizer(address, ":")
    tokenizer.nextToken // cluster name
    tokenizer.nextToken // node name
    val hostname = tokenizer.nextToken // hostname
    val port = tokenizer.nextToken.toInt // port
    new InetSocketAddress(hostname, port)
  }

  private def actorUuidsForActorAddress(actorAddress: String): Array[UUID] = uuidsForActorAddress(actorAddress) filter (_ ne null)

  /**
   * Returns a random set with replica connections of size 'replicationFactor'.
   * Default replicationFactor is 0, which returns the empty set.
   */
  private def replicaConnectionsForReplicationFactor(replicationFactor: Int = 0): Set[ActorRef] = {
    var replicas = HashSet.empty[ActorRef]
    if (replicationFactor < 1) return replicas

    connectToAllReplicas()

    val numberOfReplicas = replicaConnections.size
    val replicaConnectionsAsArray = replicaConnections.toList map {
      case (node, (address, actorRef)) ⇒ actorRef
    } // the ActorRefs

    if (numberOfReplicas < replicationFactor) {
      throw new IllegalArgumentException(
        "Replication factor [" + replicationFactor + "] is greater than the number of available nodes [" + numberOfReplicas + "]")
    } else if (numberOfReplicas == replicationFactor) {
      replicas = replicas ++ replicaConnectionsAsArray
    } else {
      val random = new java.util.Random(System.currentTimeMillis)
      while (replicas.size < replicationFactor) {
        val index = random.nextInt(numberOfReplicas)
        replicas = replicas + replicaConnectionsAsArray(index)
      }
    }
    replicas
  }

  /**
   * Connect to all available replicas unless already connected).
   */
  private def connectToAllReplicas() {
    membershipNodes foreach { node ⇒
      if (!replicaConnections.contains(node)) {
        val address = addressForNode(node)
        val clusterDaemon = Actor.remote.actorFor(RemoteClusterDaemon.ADDRESS, address.getHostName, address.getPort)
        replicaConnections.put(node, (address, clusterDaemon))
      }
    }
  }

  private[cluster] def joinMembershipNode() {
    nodeNameToAddress.put(nodeAddress.nodeName, remoteServerAddress)
    try {
      EventHandler.info(this,
        "Joining cluster as membership node [%s] on [%s]".format(nodeAddress, membershipNodePath))
      zkClient.createEphemeral(membershipNodePath, nodeAddress.toString)
    } catch {
      case e: ZkNodeExistsException ⇒
        val error = new ClusterException("Can't join the cluster. The node name [" + nodeAddress.nodeName + "] is already in by another node")
        EventHandler.error(error, this, "")
        throw error
    }
  }

  private[cluster] def joinActorsAtAddressNode() {
    ignore[ZkNodeExistsException](zkClient.createPersistent(actorsAtNodePathFor(nodeAddress.nodeName)))
  }

  private[cluster] def joinLeaderElection: Boolean = {
    EventHandler.info(this, "Node [%s] is joining leader election".format(nodeAddress.nodeName))
    leaderLock.lock
  }

  private[cluster] def failOverConnections(from: InetSocketAddress, to: InetSocketAddress) {
    clusterActorRefs.values(from) foreach (_.failOver(from, to))
  }

  private[cluster] def migrateFromFailedNodes[T <: Actor](currentSetOfClusterNodes: List[String]) = {
    findFailedNodes(currentSetOfClusterNodes).foreach { failedNodeName ⇒

      val allNodes = locallyCachedMembershipNodes.toList
      val myIndex = allNodes.indexWhere(_.endsWith(nodeAddress.nodeName))
      val failedNodeIndex = allNodes.indexWhere(_ == failedNodeName)

      // Migrate to the successor of the failed node (using a sorted circular list of the node names)
      if ((failedNodeIndex == 0 && myIndex == locallyCachedMembershipNodes.size - 1) || // No leftmost successor exists, check the tail
        (failedNodeIndex == myIndex + 1)) {
        // Am I the leftmost successor?

        // Yes I am the node to migrate the actor to (can only be one in the cluster)
        val actorUuidsForFailedNode = zkClient.getChildren(actorsAtNodePathFor(failedNodeName))
        EventHandler.debug(this,
          "Migrating actors from failed node [%s] to node [%s]: Actor UUIDs [%s]"
            .format(failedNodeName, nodeAddress.nodeName, actorUuidsForFailedNode))

        actorUuidsForFailedNode.foreach { uuid ⇒
          EventHandler.debug(this,
            "Cluster node [%s] has failed, migrating actor with UUID [%s] to [%s]"
              .format(failedNodeName, uuid, nodeAddress.nodeName))

          val actorAddress = actorAddressForUuid(uuidFrom(uuid))
          migrateWithoutCheckingThatActorResidesOnItsHomeNode( // since the ephemeral node is already gone, so can't check
            NodeAddress(nodeAddress.clusterName, failedNodeName), nodeAddress, actorAddress)

          implicit val format: Serializer = formatForActor(actorAddress)
          use(actorAddress) foreach { actor ⇒
            // FIXME remove ugly reflection when we have 1.0 final which has 'fromBinary(byte, homeAddress)(format)'
            //actor.homeAddress = remoteServerAddress
            val homeAddress = classOf[LocalActorRef].getDeclaredField("homeAddress")
            homeAddress.setAccessible(true)
            homeAddress.set(actor, Some(remoteServerAddress))

            remoteService.register(uuid, actor)
          }
        }

        // notify all available nodes that they should fail-over all connections from 'from' to 'to'
        val from = nodeNameToAddress.get(failedNodeName)
        val to = remoteServerAddress
        val command = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FAIL_OVER_CONNECTIONS)
          .setPayload(ByteString.copyFrom(Serializers.Java.toBinary((from, to))))
          .build
        membershipNodes foreach { node ⇒
          replicaConnections.get(node) foreach {
            case (_, connection) ⇒
              connection ! command
          }
        }
      }
    }
  }

  /**
   * Used when the ephemeral "home" node is already gone, so we can't check.
   */
  private def migrateWithoutCheckingThatActorResidesOnItsHomeNode(
    from: NodeAddress, to: NodeAddress, actorAddress: String) {

    actorUuidsForActorAddress(actorAddress) map { uuid ⇒
      val actorAddress = actorAddressForUuid(uuid)

      if (!isInUseOnNode(actorAddress, to)) {
        release(actorAddress)

        val newAddress = new InetSocketAddress(to.hostname, to.port)
        ignore[ZkNodeExistsException](zkClient.createPersistent(actorRegistryNodePathFor(uuid)))
        ignore[ZkNodeExistsException](zkClient.createEphemeral(actorRegistryNodePathFor(uuid, newAddress)))
        ignore[ZkNodeExistsException](zkClient.createEphemeral(actorLocationsPathFor(uuid, to)))
        ignore[ZkNodeExistsException](zkClient.createPersistent(actorAtNodePathFor(nodeAddress.nodeName, uuid)))

        ignore[ZkNoNodeException](zkClient.delete(actorLocationsPathFor(uuid, from)))
        ignore[ZkNoNodeException](zkClient.delete(actorAtNodePathFor(from.nodeName, uuid)))

        // 'use' (check out) actor on the remote 'to' node
        useActorOnNode(to.nodeName, uuid)
      }
    }
  }

  private[cluster] def findFailedNodes(nodes: List[String]): List[String] =
    (locallyCachedMembershipNodes diff Set(nodes: _*)).toList

  private[cluster] def findNewlyConnectedMembershipNodes(nodes: List[String]): List[String] =
    (Set(nodes: _*) diff locallyCachedMembershipNodes).toList

  private[cluster] def findNewlyDisconnectedMembershipNodes(nodes: List[String]): List[String] =
    (locallyCachedMembershipNodes diff Set(nodes: _*)).toList

  private[cluster] def findNewlyConnectedAvailableNodes(nodes: List[String]): List[String] =
    (Set(nodes: _*) diff locallyCachedMembershipNodes).toList

  private[cluster] def findNewlyDisconnectedAvailableNodes(nodes: List[String]): List[String] =
    (locallyCachedMembershipNodes diff Set(nodes: _*)).toList

  private def createRootClusterNode() {
    ignore[ZkNodeExistsException] {
      zkClient.create(CLUSTER_NODE, null, CreateMode.PERSISTENT)
      EventHandler.info(this, "Created node [%s]".format(CLUSTER_NODE))
    }
  }

  private def createNodeStructureIfNeeded() {
    baseNodes.foreach { path ⇒
      try {
        zkClient.create(path, null, CreateMode.PERSISTENT)
        EventHandler.debug(this, "Created node [%s]".format(path))
      } catch {
        case e: ZkNodeExistsException ⇒ {} // do nothing
        case e ⇒
          val error = new ClusterException(e.toString)
          EventHandler.error(error, this)
          throw error
      }
    }
  }

  private def registerListeners = {
    zkClient.subscribeStateChanges(stateListener)
    zkClient.subscribeChildChanges(MEMBERSHIP_NODE, membershipListener)
  }

  private def fetchMembershipChildrenNodes() {
    val membershipChildren = zkClient.getChildren(MEMBERSHIP_NODE)
    locallyCachedMembershipNodes.clear()
    membershipChildren.iterator.foreach(locallyCachedMembershipNodes.add)
  }

  private def createMBean = {
    val clusterMBean = new StandardMBean(classOf[ClusterNodeMBean]) with ClusterNodeMBean {

      import Cluster._

      override def start(): Unit = self.start()

      override def stop(): Unit = self.shutdown()

      override def disconnect() = self.disconnect()

      override def reconnect(): Unit = self.reconnect()

      override def resign(): Unit = self.resign()

      override def isConnected = self.isConnected.isOn

      override def getRemoteServerHostname = self.nodeAddress.hostname

      override def getRemoteServerPort = self.nodeAddress.port

      override def getNodeName = self.nodeAddress.nodeName

      override def getClusterName = self.nodeAddress.clusterName

      override def getZooKeeperServerAddresses = self.zkServerAddresses

      override def getMemberNodes = self.locallyCachedMembershipNodes.iterator.map(_.toString).toArray

      override def getLeader = self.leader.toString

      override def getUuidsForActorsInUse = self.uuidsForActorsInUse.map(_.toString).toArray

      override def getAddressesForActorsInUse = self.addressesForActorsInUse.map(_.toString).toArray

      override def getUuidsForClusteredActors = self.uuidsForClusteredActors.map(_.toString).toArray

      override def getAddressesForClusteredActors = self.addressesForClusteredActors.map(_.toString).toArray

      override def getNodesForActorInUseWithUuid(uuid: String) = self.nodesForActorsInUseWithUuid(stringToUuid(uuid))

      override def getNodesForActorInUseWithAddress(id: String) = self.nodesForActorsInUseWithAddress(id)

      override def getUuidsForActorsInUseOnNode(nodeName: String) = self.uuidsForActorsInUseOnNode(nodeName).map(_.toString).toArray

      override def getAddressesForActorsInUseOnNode(nodeName: String) = self.addressesForActorsInUseOnNode(nodeName).map(_.toString).toArray

      override def setConfigElement(key: String, value: String): Unit = self.setConfigElement(key, value.getBytes("UTF-8"))

      override def getConfigElement(key: String) = new String(self.getConfigElement(key), "UTF-8")

      override def removeConfigElement(key: String): Unit = self.removeConfigElement(key)

      override def getConfigElementKeys = self.getConfigElementKeys.toArray
    }

    JMX.register(clusterJmxObjectName, clusterMBean)

    // FIXME need monitoring to lookup the cluster MBean dynamically
    // Monitoring.registerLocalMBean(clusterJmxObjectName, clusterMBean)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MembershipChildListener(self: ClusterNode) extends IZkChildListener with ErrorHandler {
  def handleChildChange(parentPath: String, currentChilds: JList[String]) {
    withErrorHandler {
      if (currentChilds ne null) {
        val childList = currentChilds.toList
        if (!childList.isEmpty) EventHandler.debug(this,
          "MembershipChildListener at [%s] has children [%s]"
            .format(self.nodeAddress.nodeName, childList.mkString(" ")))
        self.findNewlyConnectedMembershipNodes(childList) foreach { name ⇒
          self.nodeNameToAddress.put(name, self.addressForNode(name)) // update 'nodename-address' map
          self.publish(Cluster.NodeConnected(name))
        }

        self.findNewlyDisconnectedMembershipNodes(childList) foreach { name ⇒
          self.nodeNameToAddress.remove(name) // update 'nodename-address' map
          self.publish(Cluster.NodeDisconnected(name))
        }

        self.locallyCachedMembershipNodes.clear()
        childList.foreach(self.locallyCachedMembershipNodes.add)
      }
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class StateListener(self: ClusterNode) extends IZkStateListener {
  def handleStateChanged(state: KeeperState) {
    state match {
      case KeeperState.SyncConnected ⇒
        EventHandler.debug(this, "Cluster node [%s] - Connected".format(self.nodeAddress))
        self.publish(Cluster.ThisNode.Connected)
      case KeeperState.Disconnected ⇒
        EventHandler.debug(this, "Cluster node [%s] - Disconnected".format(self.nodeAddress))
        self.publish(Cluster.ThisNode.Disconnected)
      case KeeperState.Expired ⇒
        EventHandler.debug(this, "Cluster node [%s] - Expired".format(self.nodeAddress))
        self.publish(Cluster.ThisNode.Expired)
    }
  }

  /**
   * Re-initialize after the zookeeper session has expired and a new session has been created.
   */
  def handleNewSession() {
    EventHandler.debug(this, "Session expired re-initializing node [%s]".format(self.nodeAddress))
    self.initializeNode()
    self.publish(Cluster.NewSession)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ErrorHandler {
  def withErrorHandler[T](body: ⇒ T) = {
    try {
      body
    } catch {
      case e: org.I0Itec.zkclient.exception.ZkInterruptedException ⇒ {
        /* ignore */
      }
      case e: Throwable ⇒
        EventHandler.error(e, this, e.toString)
        throw e
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteClusterDaemon {
  val ADDRESS = "akka-cluster-daemon".intern

  // FIXME configure functionServerDispatcher to what?
  val functionServerDispatcher = Dispatchers.newDispatcher("akka:cloud:cluster:function:server").build
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClusterDaemon(cluster: ClusterNode) extends Actor {

  import RemoteClusterDaemon._
  import Cluster._

  self.dispatcher = Dispatchers.newPinnedDispatcher(self)

  def receive: Receive = {
    case message: RemoteDaemonMessageProtocol ⇒
      EventHandler.debug(this, "Received command to RemoteClusterDaemon [%s]".format(message))
      message.getMessageType match {

        case USE ⇒
          if (message.hasActorUuid) {
            val uuid = uuidProtocolToUuid(message.getActorUuid)
            val address = cluster.actorAddressForUuid(uuid)
            implicit val format: Serializer = cluster formatForActor address
            val actors = cluster use address
          } else if (message.hasActorAddress) {
            val address = message.getActorAddress
            implicit val format: Serializer = cluster formatForActor address
            val actors = cluster use address
          } else EventHandler.warning(this,
            "None of 'uuid', or 'address' is specified, ignoring remote cluster daemon command [%s]".format(message))

        case RELEASE ⇒
          if (message.hasActorUuid) {
            cluster release cluster.actorAddressForUuid(uuidProtocolToUuid(message.getActorUuid))
          } else if (message.hasActorAddress) {
            cluster release message.getActorAddress
          } else EventHandler.warning(this,
            "None of 'uuid' or 'actorAddress'' is specified, ignoring remote cluster daemon command [%s]".format(message))

        case START      ⇒ cluster.start()

        case STOP       ⇒ cluster.shutdown()

        case DISCONNECT ⇒ cluster.disconnect()

        case RECONNECT  ⇒ cluster.reconnect()

        case RESIGN     ⇒ cluster.resign()

        case FAIL_OVER_CONNECTIONS ⇒
          val (from, to) = payloadFor(message, classOf[(InetSocketAddress, InetSocketAddress)])
          cluster.failOverConnections(from, to)

        case FUNCTION_FUN0_UNIT ⇒
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher

            def receive = {
              case f: Function0[Unit] ⇒ try {
                f()
              } finally {
                self.stop()
              }
            }
          }).start ! payloadFor(message, classOf[Function0[Unit]])

        case FUNCTION_FUN0_ANY ⇒
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher

            def receive = {
              case f: Function0[Any] ⇒ try {
                self.reply(f())
              } finally {
                self.stop()
              }
            }
          }).start forward payloadFor(message, classOf[Function0[Any]])

        case FUNCTION_FUN1_ARG_UNIT ⇒
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher

            def receive = {
              case (fun: Function[Any, Unit], param: Any) ⇒ try {
                fun(param)
              } finally {
                self.stop()
              }
            }
          }).start ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])

        case FUNCTION_FUN1_ARG_ANY ⇒
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher

            def receive = {
              case (fun: Function[Any, Unit], param: Any) ⇒ try {
                self.reply(fun(param))
              } finally {
                self.stop()
              }
            }
          }).start forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
      }

    case unknown ⇒ EventHandler.warning(this, "Unknown message [%s]".format(unknown))
  }

  private def payloadFor[T](message: RemoteDaemonMessageProtocol, clazz: Class[T]): T = {
    Serializers.Java.fromBinary(message.getPayload.toByteArray, Some(clazz)).asInstanceOf[T]
  }
}
