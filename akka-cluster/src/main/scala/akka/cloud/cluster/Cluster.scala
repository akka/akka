/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cloud.cluster

import org.apache.zookeeper._
import org.apache.zookeeper.Watcher.Event._
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.recipes.lock.{WriteLock, LockListener}

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.serialize._
import org.I0Itec.zkclient.exception._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicInteger}
import java.util.concurrent.{ConcurrentSkipListSet, CopyOnWriteArrayList, Callable, ConcurrentHashMap}
import java.util.{List => JList}
import java.net.InetSocketAddress
import javax.management.StandardMBean

import scala.collection.immutable.{HashMap, HashSet}
import scala.collection.mutable.ConcurrentMap
import scala.collection.JavaConversions._

import ClusterProtocol._
import RemoteDaemonMessageType._

import akka.util._
import akka.actor._
import akka.actor.Actor._
import akka.event.EventHandler
import akka.dispatch.{Dispatchers, Future}
import akka.remoteinterface._
import akka.config.Config._
import akka.serialization.{Format, Serializer}
import akka.serialization.Compression.LZF
import akka.AkkaException

import akka.cloud.common.JMX
import akka.cloud.common.Util._
import akka.cloud.monitoring.Monitoring
import akka.cloud.zookeeper._

import com.eaio.uuid.UUID

import com.google.protobuf.ByteString

// FIXME add watch for each node that when the entry for the node is removed then the node shuts itself down
// FIXME Provisioning data in ZK (file names etc) and files in S3 and on disk

class ClusterException(message: String) extends AkkaException(message)

/**
 * JMX MBean for the cluster service.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ClusterNodeMBean {
  def start: Unit
  def stop: Unit

  def disconnect: Unit
  def reconnect: Unit
  def resign: Unit

  def isConnected: Boolean

  def getRemoteServerHostname: String
  def getRemoteServerPort: Int

  def getNodeName: String
  def getClusterName: String
  def getZooKeeperServerAddresses: String

  def getMemberNodes: Array[String]
  def getLeader: String

  def getUuidsForClusteredActors: Array[String]
  def getIdsForClusteredActors: Array[String]
  def getClassNamesForClusteredActors: Array[String]

  def getUuidsForActorsInUse: Array[String]
  def getIdsForActorsInUse: Array[String]
  def getClassNamesForActorsInUse: Array[String]

  def getNodesForActorInUseWithUuid(uuid: String): Array[String]
  def getNodesForActorInUseWithId(id: String): Array[String]
  def getNodesForActorInUseWithClassName(className: String): Array[String]

  def getUuidsForActorsInUseOnNode(nodeName: String): Array[String]
  def getIdsForActorsInUseOnNode(nodeName: String): Array[String]
  def getClassNamesForActorsInUseOnNode(nodeName: String): Array[String]

  def setConfigElement(key: String, value: String): Unit
  def getConfigElement(key: String): AnyRef
  def removeConfigElement(key: String): Unit
  def getConfigElementKeys: Array[String]
}

/**
 * Node address holds the node name and the cluster name and can be used as a hash lookup key for a Node instance.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final case class NodeAddress(
  clusterName: String,
  nodeName: String,
  hostname: String = Cluster.lookupLocalhostName,
  port: Int        = Cluster.remoteServerPort) {
  if ((nodeName eq null)    || nodeName == "")    throw new NullPointerException("Node name must not be null or empty string")
  if ((clusterName eq null) || clusterName == "") throw new NullPointerException("Cluster name must not be null or empty string")
  override def toString = "%s:%s:%s:%s".format(clusterName, nodeName, hostname, port)
}

case class ActorAddress(
  actorUuid: UUID        = null,
  actorId: String        = Cluster.EMPTY_STRING,
  actorClassName: String = Cluster.EMPTY_STRING)

object ActorAddress {
  def forUuid(actorUuid: UUID)             = ActorAddress(actorUuid, Cluster.EMPTY_STRING, Cluster.EMPTY_STRING)
  def forId(actorId: String)               = ActorAddress(null, actorId, Cluster.EMPTY_STRING)
  def forClassName(actorClassName: String) = ActorAddress(null, actorClassName, Cluster.EMPTY_STRING)
}

/**
 * Factory object for ClusterNode. Also holds global state such as configuration data etc.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Cluster {
  val EMPTY_STRING = "".intern
  val UUID_PREFIX  = "uuid:".intern

  // config options
  val zooKeeperServers            = config.getString("akka.cloud.cluster.zookeeper-server-addresses",             "localhost:2181")
  val remoteServerPort            = config.getInt("akka.cloud.cluster.remote-server-port",                        2552)
  val sessionTimeout              = Duration(config.getInt("akka.cloud.cluster.session-timeout",                  60), TIME_UNIT).toMillis.toInt
  val connectionTimeout           = Duration(config.getInt("akka.cloud.cluster.connection-timeout",               60), TIME_UNIT).toMillis.toInt
  val maxTimeToWaitUntilConnected = Duration(config.getInt("akka.cloud.cluster.max-time-to-wait-until-connected", 30), TIME_UNIT).toMillis.toInt
  val shouldCompressData          = config.getBool("akka.cloud.cluster.use-compression",                          false)
  val enableJMX                   = config.getBool("akka.enable-jmx",                                             true)

  /**
   * Cluster membership change listener.
   * For Scala API.
   */
  trait ChangeListener {
    def notify(event: ChangeNotification, client: ClusterNode) = event match {
      case NodeConnected(name)     => nodeConnected(name, client)
      case NodeDisconnected(name)  => nodeDisconnected(name, client)
      case NewLeader(name: String) => newLeader(name, client)
      case NewSession              => thisNodeNewSession(client)
      case ThisNode.Connected      => thisNodeConnected(client)
      case ThisNode.Disconnected   => thisNodeDisconnected(client)
      case ThisNode.Expired        => thisNodeExpired(client)
    }
    def nodeConnected(node: String, client: ClusterNode) = {}
    def nodeDisconnected(node: String, client: ClusterNode) = {}
    def newLeader(name: String, client: ClusterNode) = {}
    def thisNodeNewSession(client: ClusterNode) = {}
    def thisNodeConnected(client: ClusterNode) = {}
    def thisNodeDisconnected(client: ClusterNode) = {}
    def thisNodeExpired(client: ClusterNode) = {}
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

  type Nodes = HashMap[NodeAddress, ClusterNode]

  val defaultSerializer = new SerializableSerializer

  private val _zkServer     = new AtomicReference[Option[ZkServer]](None)
  private val _nodes        = new AtomicReference(new Nodes)
  private val _clusterNames = new ConcurrentSkipListSet[String]

  private[cluster] def updateNodes(f: Nodes => Nodes) =
    while (Some(_nodes.get).map(node => _nodes.compareAndSet(node, f(node)) == false).get) {}

  /**
   * Looks up the local hostname.
   */
  def lookupLocalhostName = NetworkUtil.getLocalhostName

  /**
   * Returns all the nodes created by this Cluster object, e.g. created in this class loader hierarchy in this JVM.
   */
  def nodes = _nodes.get

  /**
   * Returns an Array with NodeAddress for all the nodes in a specific cluster.
   */
  def nodesInCluster(clusterName: String): Array[NodeAddress] = _nodes.get.filter(_._1 == clusterName).map(_._1).toArray

  /**
   * Returns the NodeAddress for a  random node in a specific cluster.
   */
  def randomNodeInCluster(clusterName: String): NodeAddress = {
    val nodes = nodesInCluster(clusterName)
    val random = new java.util.Random
    nodes(random.nextInt(nodes.length))
  }

  /**
   * Returns the names of all clusters that this JVM is connected to.
   */
  def clusters: Array[String] = _clusterNames.toList.toArray

  /**
   * Returns the node for a specific NodeAddress.
   */
  def nodeFor(nodeAddress: NodeAddress) = _nodes.get()(nodeAddress)

  /**
   * Creates a new cluster node; ClusterNode.
   */
  def apply(
    nodeAddress: NodeAddress,
    zkServerAddresses: String = Cluster.zooKeeperServers,
    serializer: ZkSerializer  = Cluster.defaultSerializer): ClusterNode =
    newNode(nodeAddress, zkServerAddresses, serializer)

  /**
   * Creates a new cluster node; ClusterNode.
   */
  def newNode(nodeAddress: NodeAddress): ClusterNode =
    newNode(nodeAddress, Cluster.zooKeeperServers, Cluster.defaultSerializer)

  /**
   * Creates a new cluster node; ClusterNode.
   */
  def newNode(nodeAddress: NodeAddress, zkServerAddresses: String): ClusterNode =
    newNode(nodeAddress, zkServerAddresses, Cluster.defaultSerializer)

  /**
   * Creates a new cluster node; ClusterNode.
   */
  def newNode(nodeAddress: NodeAddress, serializer: ZkSerializer): ClusterNode =
    newNode(nodeAddress, Cluster.zooKeeperServers, serializer)

  /**
   * Creates a new cluster node; ClusterNode.
   */
  def newNode(
    nodeAddress: NodeAddress,
    zkServerAddresses: String,
    serializer: ZkSerializer): ClusterNode = {

    if (nodeAddress eq null) throw new IllegalArgumentException("NodeAddress can't be null")

    val node = new ClusterNode(
      nodeAddress,
      if ((zkServerAddresses eq null) || zkServerAddresses == "") Cluster.zooKeeperServers else zkServerAddresses,
      if (serializer eq null) Cluster.defaultSerializer else serializer)

    // FIXME Cluster nodes are never removed?
    updateNodes(_ + (nodeAddress -> node))
    _clusterNames add nodeAddress.clusterName
    node
  }

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
      EventHandler.info(this,
          "Starting local ZooKeeper server on\n\tport [%s]\n\tdata path [%s]\n\tlog path [%s]\n\ttick time [%s]"
            .format(port, dataPath, logPath, tickTime))
      val zkServer = AkkaZooKeeper.startLocalServer(dataPath, logPath, port, tickTime)
      _zkServer.set(Some(zkServer))
      zkServer
    } catch {
      case e: Throwable =>
        EventHandler.error(e, this, "Could not start local ZooKeeper cluster")
        throw e
    }
  }

  /**
   * Resets all clusters managed connected to in this JVM.
   * <p/>
   * <b>WARNING: Use with care</b>
   */
  def reset(): Unit = withPrintStackTraceOnError {
    EventHandler.info(this, "Resetting all clusters connected to in this JVM")
    if (!clusters.isEmpty) {
      nodes foreach { tp =>
        val (_, node) = tp
        node.disconnect
        node.remoteService.shutdown
      }
      implicit val zkClient = newZkClient
      clusters foreach (resetNodesInCluster(_))
      ignore[ZkNoNodeException](zkClient.deleteRecursive(ZooKeeperBarrier.BarriersNode))
      zkClient.close
    }
  }

  /**
   * Resets all nodes in a specific cluster.
   */
  def resetNodesInCluster(clusterName: String)(implicit zkClient: AkkaZkClient = newZkClient) = withPrintStackTraceOnError {
    EventHandler.info(this, "Resetting nodes in cluster [%s]".format(clusterName))
    ignore[ZkNoNodeException](zkClient.deleteRecursive("/" + clusterName))
  }

  /**
   * Shut down the local ZooKeeper server.
   */
  def shutdownLocalCluster() = withPrintStackTraceOnError {
    EventHandler.info(this, "Shuts down local cluster")
    reset
    _zkServer.get.foreach(_.shutdown)
    _zkServer.set(None)
  }

  /**
   * Creates a new AkkaZkClient.
   */
  def newZkClient: AkkaZkClient = new AkkaZkClient(zooKeeperServers, sessionTimeout, connectionTimeout, defaultSerializer)

  def uuidToString(uuid: UUID): String = uuid.toString

  def stringToUuid(uuid: String): UUID = {
    if (uuid eq null) throw new ClusterException("UUID is null")
    if (uuid == "")   throw new ClusterException("UUID is an empty string")
    try { new UUID(uuid) }
    catch {
      case e: StringIndexOutOfBoundsException =>
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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ClusterNode private[akka] (
  val nodeAddress: NodeAddress,
  val zkServerAddresses: String,
  val serializer: ZkSerializer) extends ErrorHandler { self =>

  if (nodeAddress eq null) throw new IllegalArgumentException("'nodeAddress' can not be 'null'")

  import Cluster._

  EventHandler.info(this,
    ("\nCreating cluster node with" +
    "\n\tnode name = [%s]" +
    "\n\tcluster name = [%s]" +
    "\n\tzookeeper server addresses = [%s]" +
    "\n\tserializer = [%s]")
    .format(nodeAddress.nodeName, nodeAddress.clusterName, zkServerAddresses, serializer))

  val remoteClientLifeCycleListener = actorOf(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) => client.shutdownClientModule
      case RemoteClientDisconnected(client, address) => client.shutdownClientModule
      case _                                         => //ignore other
    }
  }).start

  val remoteDaemon = actorOf(new RemoteClusterDaemon(this)).start

  val remoteService: RemoteSupport = {
    val remote = new akka.remote.netty.NettyRemoteSupport
    remote.start(nodeAddress.hostname, nodeAddress.port)
    remote.register(RemoteClusterDaemon.ID, remoteDaemon)
    remote.addListener(remoteClientLifeCycleListener)
    remote
  }
  val remoteServerAddress: InetSocketAddress = remoteService.address

  val clusterJmxObjectName = JMX.nameFor(nodeAddress.hostname, "monitoring", "cluster")

  // static nodes
  val CLUSTER_NODE              = "/" + nodeAddress.clusterName
  val MEMBERSHIP_NODE           = CLUSTER_NODE + "/members"
  val CONFIGURATION_NODE        = CLUSTER_NODE + "/config"
  val PROVISIONING_NODE         = CLUSTER_NODE + "/provisioning"
  val ACTOR_REGISTRY_NODE       = CLUSTER_NODE + "/actor-registry"
  val ACTOR_LOCATIONS_NODE      = CLUSTER_NODE + "/actor-locations"
  val ACTOR_ID_TO_UUIDS_NODE    = CLUSTER_NODE + "/actor-id-to-uuids"
  val ACTOR_CLASS_TO_UUIDS_NODE = CLUSTER_NODE + "/actor-class-to-uuids"
  val ACTORS_AT_ADDRESS_NODE    = CLUSTER_NODE + "/actors-at-address"
  val baseNodes = List(
    CLUSTER_NODE,
    MEMBERSHIP_NODE,
    ACTOR_REGISTRY_NODE,
    ACTOR_LOCATIONS_NODE,
    ACTORS_AT_ADDRESS_NODE,
    ACTOR_ID_TO_UUIDS_NODE,
    ACTOR_CLASS_TO_UUIDS_NODE,
    CONFIGURATION_NODE,
    PROVISIONING_NODE)

  val LEADER_ELECTION_NODE = CLUSTER_NODE + "/leader" // should NOT be part of 'baseNodes' only used by 'leaderLock'

  val isConnected    = new Switch(false)
  val isLeader       = new AtomicBoolean(false)
  val electionNumber = new AtomicInteger(Integer.MAX_VALUE)

  private val membershipNodePath   = membershipNodePathFor(nodeAddress.nodeName)

  // local caches of ZK data
  private[akka] val locallyCachedMembershipNodes                                = new ConcurrentSkipListSet[String]()
  private[akka] val nodeNameToAddress: ConcurrentMap[String, InetSocketAddress] = new ConcurrentHashMap[String, InetSocketAddress]
  private[akka] val locallyCheckedOutActors: ConcurrentMap[UUID, Array[Byte]]   = new ConcurrentHashMap[UUID, Array[Byte]]

  def membershipNodes: Array[String] = locallyCachedMembershipNodes.toList.toArray.asInstanceOf[Array[String]]

  private[akka] val replicaConnections: ConcurrentMap[String, Tuple2[InetSocketAddress, ActorRef]] =
    new ConcurrentHashMap[String, Tuple2[InetSocketAddress, ActorRef]]

  // zookeeper listeners
  private val stateListener        = new StateListener(this)
  private val membershipListener   = new MembershipChildListener(this)

  // cluster node listeners
  private val changeListeners      = new CopyOnWriteArrayList[ChangeListener]()

  // Address -> ClusterActorRef
  private val clusterActorRefs     = new Index[InetSocketAddress, ClusterActorRef]

  // resources
  private[cluster] val zkClient = new AkkaZkClient(zkServerAddresses, sessionTimeout, connectionTimeout, serializer)

  private[cluster] val leaderElectionCallback = new LockListener {
    def lockAcquired {
      EventHandler.info(this, "Node [%s] is the new leader".format(self.nodeAddress.nodeName))
      self.isLeader.set(true)
      self.publish(Cluster.NewLeader(self.nodeAddress.nodeName))
    }

    def lockReleased {
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
      initializeNode
    }
    this
  }

  def stop(): Unit = isConnected switchOff {
    ignore[ZkNoNodeException](zkClient.deleteRecursive(membershipNodePath))

    locallyCachedMembershipNodes.clear
    locallyCheckedOutActors.clear

    replicaConnections.toList.foreach({ case (_, (address, _)) =>
      remote.shutdownClientConnection(address)  // shut down client connections
    })

    remoteService.shutdown // shutdown server

    remoteClientLifeCycleListener.stop
    remoteDaemon.stop

    // for monitoring remote listener
    registry.actors.filter(remoteService.hasListener).foreach(_.stop)

    replicaConnections.clear
    updateNodes(_ - nodeAddress)

    disconnect()
    EventHandler.info(this, "Cluster node shut down [%s]".format(nodeAddress))
  }

  def disconnect(): ClusterNode = {
    zkClient.unsubscribeAll
    zkClient.close
    this
  }

  def reconnect(): ClusterNode = {
    zkClient.reconnect
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

  private[cluster] def publish(change: ChangeNotification) = changeListeners.iterator.foreach(_.notify(change, this))

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
  def resign = if (isLeader.get) leaderLock.unlock

  // =======================================
  // Actor
  // =======================================

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorClass: Class[T])
    (implicit format: Format[T]): ClusterNode = store(Actor.actorOf(actorClass).start, 0, false)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorClass: Class[T], replicationFactor: Int)
    (implicit format: Format[T]): ClusterNode = store(Actor.actorOf(actorClass).start, replicationFactor, false)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorClass: Class[T], serializeMailbox: Boolean)
    (implicit format: Format[T]): ClusterNode = store(Actor.actorOf(actorClass).start, 0, serializeMailbox)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorClass: Class[T], replicationFactor: Int, serializeMailbox: Boolean)
    (implicit format: Format[T]): ClusterNode =
    store(Actor.actorOf(actorClass).start, replicationFactor, serializeMailbox)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorRef: ActorRef)
    (implicit format: Format[T]): ClusterNode = store(actorRef, 0, false)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorRef: ActorRef, replicationFactor: Int)
    (implicit format: Format[T]): ClusterNode = store(actorRef, replicationFactor, false)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorRef: ActorRef, serializeMailbox: Boolean)
    (implicit format: Format[T]): ClusterNode = store(actorRef, 0, serializeMailbox)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor]
    (actorRef: ActorRef, replicationFactor: Int, serializeMailbox: Boolean)
    (implicit format: Format[T]): ClusterNode = if (isConnected.isOn) {

    import akka.serialization.ActorSerialization._

    if (!actorRef.isInstanceOf[LocalActorRef]) throw new IllegalArgumentException(
      "'actorRef' must be an instance of 'LocalActorRef' [" + actorRef.getClass.getName + "]")

    val uuid = actorRef.uuid
    EventHandler.debug(this,
      "Clustering actor [%s] with UUID [%s]".format( actorRef.actorClassName, uuid))

    val actorBytes = if (shouldCompressData) LZF.compress(toBinary(actorRef, serializeMailbox)(format))
                     else toBinary(actorRef)(format)
    val actorRegistryPath = actorRegistryNodePathFor(uuid)

    // create UUID -> Array[Byte] for actor registry
    if (zkClient.exists(actorRegistryPath)) zkClient.writeData(actorRegistryPath, actorBytes) // FIXME check for size and warn if too big
    else {
      zkClient.retryUntilConnected(new Callable[Either[String, Exception]]() {
          def call: Either[String, Exception] = {
            try {
              Left(zkClient.connection.create(actorRegistryPath, actorBytes, CreateMode.PERSISTENT))
            } catch { case e: KeeperException.NodeExistsException => Right(e) }
          }
      }) match {
        case Left(path)       => path
        case Right(exception) => actorRegistryPath
      }

      // create UUID -> Format registry
      try {
        zkClient.createPersistent(actorRegistryFormatNodePathFor(uuid), format)
      } catch {
        case e: ZkNodeExistsException => zkClient.writeData(actorRegistryFormatNodePathFor(uuid), format)
      }

      // create UUID -> ID registry
      try {
        zkClient.createPersistent(actorRegistryActorIdNodePathFor(uuid), actorRef.id)
      } catch {
        case e: ZkNodeExistsException => zkClient.writeData(actorRegistryActorIdNodePathFor(uuid), actorRef.id)
      }

      // create UUID -> class name registry
      try {
        zkClient.createPersistent(actorRegistryActorClassNameNodePathFor(uuid), actorRef.actorClassName)
      } catch {
        case e: ZkNodeExistsException => zkClient.writeData(actorRegistryActorClassNameNodePathFor(uuid), actorRef.actorClassName)
      }

      // create UUID -> Address registry
      ignore[ZkNodeExistsException]( zkClient.createPersistent(actorRegistryAddressNodePathFor(uuid)) )

      // create UUID -> Node registry
      ignore[ZkNodeExistsException]( zkClient.createPersistent(actorLocationsNodePathFor(uuid)) )

      // create ID -> UUIDs registry
      ignore[ZkNodeExistsException]( zkClient.createPersistent(actorIdToUuidsNodePathFor(actorRef.id)) )
      ignore[ZkNodeExistsException]( zkClient.createPersistent("%s/%s".format(actorIdToUuidsNodePathFor(actorRef.id), uuid)) )

      // create class name -> UUIDs registry
      ignore[ZkNodeExistsException]( zkClient.createPersistent(actorClassNameToUuidsNodePathFor(actorRef.actorClassName)) )
      ignore[ZkNodeExistsException]( zkClient.createPersistent("%s/%s".format(actorClassNameToUuidsNodePathFor(actorRef.actorClassName), uuid)) )
    }

    val command = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorUuid(uuidToUuidProtocol(uuid))
      .build
    replicaConnectionsForReplicationFactor(replicationFactor) foreach { connection =>
      connection ! command
    }

    this
  } else throw new ClusterException("Not connected to cluster")

  /**
   * Removes actor by type from the cluster.
   * <pre>
   *   clusterNode remove classOf[MyActor]
   * </pre>
   */
  def remove[T <: Actor](actorClass: Class[T]): ClusterNode = remove(ActorAddress(actorClassName = actorClass.getName))

  /**
   * Removes actor with UUID from the cluster.
   */
  def remove(actorAddress: ActorAddress): ClusterNode = {

    def removeByUuid(actorUuid: UUID) = {
      releaseActorOnAllNodes(actorUuid)

      locallyCheckedOutActors.remove(actorUuid)
      // warning: ordering matters here
      ignore[ZkNoNodeException](zkClient.deleteRecursive(actorIdToUuidsNodePathFor(actorIdForUuid(actorUuid))))               // remove ID to UUID mapping
      ignore[ZkNoNodeException](zkClient.deleteRecursive(actorClassNameToUuidsNodePathFor(actorClassNameForUuid(actorUuid)))) // remove class name to UUID mapping
      ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAtAddressNodePathFor(nodeAddress.nodeName, actorUuid)))
      ignore[ZkNoNodeException](zkClient.deleteRecursive(actorRegistryNodePathFor(actorUuid)))
      ignore[ZkNoNodeException](zkClient.deleteRecursive(actorLocationsNodePathFor(actorUuid)))
    }

    isConnected ifOn {
      // remove by UUID
      if (actorAddress.actorUuid ne null) {
        EventHandler.debug(this,
          "Removing actor with UUID [%s] from cluster".format(actorAddress.actorUuid))
        removeByUuid(actorAddress.actorUuid)

      // remove by ID
      } else if (actorAddress.actorId != EMPTY_STRING) {
        EventHandler.debug(this,
          "Removing actor(s) with ID [%s] from cluster".format(actorAddress.actorId))
        uuidsForActorId(actorAddress.actorId) foreach (uuid => removeByUuid(uuid))

      // remove by class name
      } else if (actorAddress.actorClassName != EMPTY_STRING) {
        EventHandler.debug(this,
          "Removing actor(s) with class name [%s] from cluster".format(actorAddress.actorClassName))
        uuidsForActorClassName(actorAddress.actorClassName) foreach (uuid => removeByUuid(uuid))

      } else throw new IllegalArgumentException(
        "You need to pass in at least one of 'actorUuid' or 'actorId' or 'actorClassName' to 'ClusterNode.remove(..)'")
    }
    this
  }

  /**
   * Is the actor with uuid clustered or not?
   */
  def isClustered(actorAddress: ActorAddress): Boolean = if (isConnected.isOn) {
    actorUuidsForActorAddress(actorAddress) map { uuid =>
      zkClient.exists(actorRegistryNodePathFor(uuid))
    } exists (_ == true)
  } else false

  /**
   * Is the actor with uuid in use on 'this' node or not?
   */
  def isInUseOnNode(actorAddress: ActorAddress): Boolean = isInUseOnNode(actorAddress, nodeAddress)

  /**
   * Is the actor with uuid in use or not?
   */
  def isInUseOnNode(actorAddress: ActorAddress, node: NodeAddress): Boolean = if (isConnected.isOn) {
    actorUuidsForActorAddress(actorAddress) map { uuid =>
      zkClient.exists(actorLocationsNodePathFor(uuid, node))
    } exists (_ == true)
  } else false

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: ActorAddress)(
    implicit format: Format[T] = formatForActor(actorAddress)): Array[LocalActorRef] = if (isConnected.isOn) {

    import akka.serialization.ActorSerialization._

    actorUuidsForActorAddress(actorAddress) map { uuid =>
      EventHandler.debug(this,
        "Checking out actor with UUID [%s] to be used on node [%s]".format(uuid, nodeAddress.nodeName))

      ignore[ZkNodeExistsException](zkClient.createPersistent(actorAtAddressNodePathFor(nodeAddress.nodeName, uuid), true))
      ignore[ZkNodeExistsException](zkClient.createEphemeral(actorLocationsNodePathFor(uuid, nodeAddress)))

      // set home address
      ignore[ZkNodeExistsException](zkClient.createPersistent(actorRegistryAddressNodePathFor(uuid)))
      ignore[ZkNodeExistsException](zkClient.createEphemeral(actorRegistryAddressNodePathFor(uuid, remoteServerAddress)))

      val actorPath = actorRegistryNodePathFor(uuid)
      zkClient.retryUntilConnected(new Callable[Either[Array[Byte], Exception]]() {
          def call: Either[Array[Byte], Exception] = {
            try {
              Left(if (shouldCompressData) LZF.uncompress(zkClient.connection.readData(actorPath, new Stat, false))
                   else zkClient.connection.readData(actorPath, new Stat, false))
            } catch { case e: KeeperException.NodeExistsException => Right(e) }
          }
      }) match {
        case Left(bytes) =>
          locallyCheckedOutActors += (uuid -> bytes)
          // FIXME switch to ReplicatedActorRef here
          // val actor = new ReplicatedActorRef(fromBinary[T](bytes, remoteServerAddress)(format))
          val actor = fromBinary[T](bytes, remoteServerAddress)(format)
          remoteService.register(UUID_PREFIX + uuid, actor) // clustered refs are always registered and looked up by UUID
          actor.start
          actor.asInstanceOf[LocalActorRef]
        case Right(exception) => throw exception
      }
    }
  } else Array.empty[LocalActorRef]

  /**
   * Using (checking out) all actors with a specific UUID on all nodes in the cluster.
   */
  def useActorOnAllNodes(uuid: UUID): Unit = isConnected ifOn {
    EventHandler.debug(this,
      "Using (checking out) all actors with UUID [%s] on all nodes in cluster".format(uuid))
    val command = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorUuid(uuidToUuidProtocol(uuid))
      .build
    membershipNodes foreach { node =>
      replicaConnections.get(node) foreach { case (_, connection) =>
        connection ! command
      }
    }
  }

  /**
   * Using (checking out) specific UUID on a specefic node.
   */
  def useActorOnNode(node: String, uuid: UUID): Unit = isConnected ifOn {
    replicaConnections.get(node) foreach { case (_, connection) =>
      connection ! RemoteDaemonMessageProtocol.newBuilder
        .setMessageType(USE)
        .setActorUuid(uuidToUuidProtocol(uuid))
        .build
    }
  }

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorAddress: ActorAddress): Unit = isConnected ifOn {
    actorUuidsForActorAddress(actorAddress) foreach { uuid =>
      EventHandler.debug(this,
        "Releasing actor with UUID [%s] after usage".format(uuid))
      locallyCheckedOutActors.remove(uuid)
      ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAtAddressNodePathFor(nodeAddress.nodeName, uuid)))
      ignore[ZkNoNodeException](zkClient.delete(actorAtAddressNodePathFor(nodeAddress.nodeName, uuid)))
      ignore[ZkNoNodeException](zkClient.delete(actorLocationsNodePathFor(uuid, nodeAddress)))
      ignore[ZkNoNodeException](zkClient.delete(actorRegistryAddressNodePathFor(uuid, remoteServerAddress)))
    }
  }

  /**
   * Releases (checking in) all actors with a specific UUID on all nodes in the cluster where the actor is in 'use'.
   */
  def releaseActorOnAllNodes(uuid: UUID): Unit = isConnected ifOn {
    EventHandler.debug(this,
      "Releasing (checking in) all actors with UUID [%s] on all nodes in cluster".format(uuid))
    val command = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(RELEASE)
      .setActorUuid(uuidToUuidProtocol(uuid))
      .build
    nodesForActorsInUseWithUuid(uuid) foreach { node =>
      replicaConnections.get(node) foreach { case (_, connection) =>
        connection ! command
      }
    }
  }

  /**
   * Creates an ActorRef with a Router to a set of clustered actors.
   */
  def ref(actorAddress: ActorAddress, router: Router.RouterType): ActorRef = if (isConnected.isOn) {

    val addresses = addressesForActor(actorAddress)
    val actorType = ActorType.ScalaActor // FIXME later we also want to suppot TypedActor, then 'actorType' needs to be configurable

    EventHandler.debug(this,
      "Creating cluster actor ref with router [%s] for actors [%s]".format(router, addresses.mkString(", ")))

    def registerClusterActorRefForAddress(actorRef: ClusterActorRef, addresses: Array[(UUID, InetSocketAddress)]) =
      addresses foreach { case (_, address) => clusterActorRefs.put(address, actorRef) }

    def refByUuid(actorUuid: UUID): ActorRef = {
      val actorClassName = actorClassNameForUuid(actorUuid)
      val actor = Router newRouter (
        router, addresses,
        uuidToString(actorUuid), actorClassName,
        Cluster.lookupLocalhostName, Cluster.remoteServerPort, // set it to local hostname:port
        Actor.TIMEOUT, actorType)
      registerClusterActorRefForAddress(actor, addresses)
      actor
    }

    def refById(actorId: String): ActorRef = {
      val uuids          = uuidsForActorId(actorId)
      val actorClassName = uuids.map(uuid => actorClassNameForUuid(uuid)).head
      if (actorClassName eq null) throw new IllegalStateException(
        "Actor class name for actor with UUID [" + uuids.head + "] could not be retrieved")
      val actor = Router newRouter (
        router, addresses,
        actorId, actorClassName,
        Cluster.lookupLocalhostName, Cluster.remoteServerPort, // set it to local hostname:port
        Actor.TIMEOUT, actorType)
      registerClusterActorRefForAddress(actor, addresses)
      actor
    }

    def refByClassName(actorClassName: String): ActorRef = {
      val actor = Router newRouter (
        router, addresses,
        actorClassName, actorClassName,
        Cluster.lookupLocalhostName, Cluster.remoteServerPort, // set it to local hostname:port
        Actor.TIMEOUT, actorType)
      registerClusterActorRefForAddress(actor, addresses)
      actor
    }

    val actorUuid      = actorAddress.actorUuid
    val actorId        = actorAddress.actorId
    val actorClassName = actorAddress.actorClassName
         if ((actorUuid     ne null)        && actorId    == EMPTY_STRING && actorClassName == EMPTY_STRING) refByUuid(actorUuid)
    else if (actorId        != EMPTY_STRING && (actorUuid eq null)        && actorClassName == EMPTY_STRING) refById(actorId)
    else if (actorClassName != EMPTY_STRING && (actorUuid eq null)        && actorId        == EMPTY_STRING) refByClassName(actorClassName)
    else throw new IllegalArgumentException("You need to pass in either 'actorUuid' or 'actorId' or 'actorClassName' and only one of them")
  } else throw new ClusterException("Not connected to cluster")

  /**
   * Migrate the actor from 'this' node to node 'to'.
   */
  def migrate(to: NodeAddress, actorAddress: ActorAddress): Unit = migrate(nodeAddress, to, actorAddress)

  /**
   * Migrate the actor from node 'from' to node 'to'.
   */
  def migrate(
    from: NodeAddress, to: NodeAddress, actorAddress: ActorAddress): Unit = isConnected ifOn {
    if (from eq null) throw new IllegalArgumentException("NodeAddress 'from' can not be 'null'")
    if (to eq null)   throw new IllegalArgumentException("NodeAddress 'to' can not be 'null'")
    if (isInUseOnNode(actorAddress, from)) {
      migrateWithoutCheckingThatActorResidesOnItsHomeNode(from, to, actorAddress)
    } else {
      throw new ClusterException("Can't move actor from node [" + from + "] since it does not exist on this node")
    }
  }

  /**
   * Returns the UUIDs of all actors checked out on this node.
   */
  def uuidsForActorsInUse: Array[UUID] = uuidsForActorsInUseOnNode(nodeAddress.nodeName)

  /**
   * Returns the IDs of all actors checked out on this node.
   */
  def idsForActorsInUse: Array[String] = actorIdsForUuids(uuidsForActorsInUse)

  /**
   * Returns the class names of all actors checked out on this node.
   */
  def classNamesForActorsInUse: Array[String] = actorClassNamesForUuids(uuidsForActorsInUse)

  /**
   * Returns the UUIDs of all actors registered in this cluster.
   */
  def uuidsForClusteredActors: Array[UUID] = if (isConnected.isOn) {
    zkClient.getChildren(ACTOR_REGISTRY_NODE).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]]
  } else Array.empty[UUID]

  /**
   * Returns the IDs of all actors registered in this cluster.
   */
  def idsForClusteredActors: Array[String] = actorIdsForUuids(uuidsForClusteredActors)

  /**
   * Returns the class names of all actors registered in this cluster.
   */
  def classNamesForClusteredActors: Array[String] = actorClassNamesForUuids(uuidsForClusteredActors)

  /**
   * Returns the actor id for the actor with a specific UUID.
   */
  def actorIdForUuid(uuid: UUID): String = if (isConnected.isOn) {
    try { zkClient.readData(actorRegistryActorIdNodePathFor(uuid)).asInstanceOf[String] }
    catch { case e: ZkNoNodeException => "" }
  } else ""

  /**
   * Returns the actor ids for all the actors with a specific UUID.
   */
  def actorIdsForUuids(uuids: Array[UUID]): Array[String] = uuids map (actorIdForUuid(_)) filter (_ != "")

  /**
   * Returns the actor class name for the actor with a specific UUID.
   */
  def actorClassNameForUuid(uuid: UUID): String = if (isConnected.isOn) {
    try { zkClient.readData(actorRegistryActorClassNameNodePathFor(uuid)).asInstanceOf[String] }
    catch { case e: ZkNoNodeException => "" }
  } else ""

  /**
   * Returns the actor class names for all the actors with a specific UUID.
   */
  def actorClassNamesForUuids(uuids: Array[UUID]): Array[String] = uuids map (actorClassNameForUuid(_)) filter (_ != "")

  /**
   * Returns the actor UUIDs for actor ID.
   */
  def uuidsForActorId(actorId: String): Array[UUID] = if (isConnected.isOn) {
    try { zkClient.getChildren(actorIdToUuidsNodePathFor(actorId)).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]] }
    catch { case e: ZkNoNodeException => Array[UUID]() }
  } else Array.empty[UUID]

  /**
   * Returns the actor UUIDs for actor class name.
   */
  def uuidsForActorClassName(actorClassName: String): Array[UUID] = if (isConnected.isOn) {
    try { zkClient.getChildren(actorClassNameToUuidsNodePathFor(actorClassName)).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]] }
    catch { case e: ZkNoNodeException => Array[UUID]() }
  } else Array.empty[UUID]

  /**
   * Returns the node names of all actors in use with UUID.
   */
  def nodesForActorsInUseWithUuid(uuid: UUID): Array[String] = if (isConnected.isOn) {
    try { zkClient.getChildren(actorLocationsNodePathFor(uuid)).toList.toArray.asInstanceOf[Array[String]] }
    catch { case e: ZkNoNodeException => Array[String]() }
  } else Array.empty[String]

  /**
   * Returns the node names of all actors in use with id.
   */
  def nodesForActorsInUseWithId(id: String): Array[String] = if (isConnected.isOn) {
    flatten {
      actorUuidsForActorAddress(ActorAddress(null, id, EMPTY_STRING)) map { uuid =>
        try { zkClient.getChildren(actorLocationsNodePathFor(uuid)).toList.toArray.asInstanceOf[Array[String]] }
        catch { case e: ZkNoNodeException => Array[String]() }
      }
    }
  } else Array.empty[String]

  /**
   * Returns the node names of all actors in use with class name.
   */
  def nodesForActorsInUseWithClassName(className: String): Array[String] = if (isConnected.isOn) {
    flatten {
      actorUuidsForActorAddress(ActorAddress(null, EMPTY_STRING, className)) map { uuid =>
        try { zkClient.getChildren(actorLocationsNodePathFor(uuid)).toList.toArray.asInstanceOf[Array[String]] }
        catch { case e: ZkNoNodeException => Array[String]() }
      }
    }
  } else Array.empty[String]

  /**
   * Returns the UUIDs of all actors in use registered on a specific node.
   */
  def uuidsForActorsInUseOnNode(nodeName: String): Array[UUID] = if (isConnected.isOn) {
    try { zkClient.getChildren(actorsAtAddressNodePathFor(nodeName)).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]] }
    catch { case e: ZkNoNodeException => Array[UUID]() }
  } else Array.empty[UUID]

  /**
   * Returns the IDs of all actors in use registered on a specific node.
   */
  def idsForActorsInUseOnNode(nodeName: String): Array[String] = if (isConnected.isOn) {
    val uuids =
      try { zkClient.getChildren(actorsAtAddressNodePathFor(nodeName)).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]] }
      catch { case e: ZkNoNodeException => Array[UUID]() }
    actorIdsForUuids(uuids)
  } else Array.empty[String]

  /**
   * Returns the class namess of all actors in use registered on a specific node.
   */
  def classNamesForActorsInUseOnNode(nodeName: String): Array[String] = if (isConnected.isOn) {
   val uuids =
     try { zkClient.getChildren(actorsAtAddressNodePathFor(nodeName)).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]] }
     catch { case e: ZkNoNodeException => Array[UUID]() }
    actorClassNamesForUuids(uuids)
  } else Array.empty[String]

  /**
   * Returns Format for actor with UUID.
   */
  def formatForActor[T <: Actor](actorAddress: ActorAddress): Format[T] = {

    val formats = actorUuidsForActorAddress(actorAddress) map { uuid =>
      zkClient.readData(actorRegistryFormatNodePathFor(uuid), new Stat).asInstanceOf[Format[T]]
    }

    val format = formats.head
    if (formats.isEmpty) throw new IllegalStateException("No Format found for [%s]".format(actorAddress))
    if (formats map (_ == format) exists (_ == false)) throw new IllegalStateException(
      "Multiple Format classes found for [%s]".format(actorAddress))
    format
  }

  /**
   * Returns home address for actor with UUID.
   */
  def addressesForActor(actorAddress: ActorAddress): Array[(UUID, InetSocketAddress)] = {
    try {
      for {
        uuid    <- actorUuidsForActorAddress(actorAddress)
        address <- zkClient.getChildren(actorRegistryAddressNodePathFor(uuid)).toList
      } yield {
        val tokenizer = new java.util.StringTokenizer(address, ":")
        val hostname  = tokenizer.nextToken       // hostname
        val port      = tokenizer.nextToken.toInt // port
        (uuid, new InetSocketAddress(hostname, port))
      }
    } catch {
      case e: ZkNoNodeException => Array[(UUID, InetSocketAddress)]()
    }
  }

  // =======================================
  // Compute Grid
  // =======================================

  /**
   * Send a function 'Function0[Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   */
  def send(f: Function0[Unit], replicationFactor: Int): Unit = {
    val message = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(FUNCTION_FUN0_UNIT)
      .setPayload(ByteString.copyFrom(Serializer.Java.toBinary(f)))
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
      .setPayload(ByteString.copyFrom(Serializer.Java.toBinary(f)))
      .build
    val results = replicaConnectionsForReplicationFactor(replicationFactor) map (_ !!! message)
    results.toList.asInstanceOf[List[Future[Any]]]
  }

  /**
   * Send a function 'Function1[Any, Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   */
  def send(f: Function1[Any, Unit], arg: Any, replicationFactor: Int): Unit = {
    val message = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(FUNCTION_FUN1_ARG_UNIT)
      .setPayload(ByteString.copyFrom(Serializer.Java.toBinary((f, arg))))
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
      .setPayload(ByteString.copyFrom(Serializer.Java.toBinary((f, arg))))
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
            Left(zkClient.connection.create(configurationNodePathFor(key), compressedBytes, CreateMode.PERSISTENT))
          } catch { case e: KeeperException.NodeExistsException =>
            try {
              Left(zkClient.connection.writeData(configurationNodePathFor(key), compressedBytes))
            } catch { case e: Exception => Right(e) }
          }
        }
      }) match {
        case Left(_) => { /* do nothing */ }
        case Right(exception) => throw exception
      }
  }

  /**
   * Returns the config element for the key or NULL if no element exists under the key.
   */
  def getConfigElement(key: String): Array[Byte] = try {
    zkClient.connection.readData(configurationNodePathFor(key), new Stat, true)
  } catch {
    case e: KeeperException.NoNodeException => null
  }

  def removeConfigElement(key: String) = ignore[ZkNoNodeException]{
    EventHandler.debug(this,
      "Removing config element with key [%s] from cluster registry".format(key))
    zkClient.deleteRecursive(configurationNodePathFor(key))
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

  private[cluster] def membershipNodePathFor(node: String)                      = "%s/%s".format(MEMBERSHIP_NODE, node)

  private[cluster] def configurationNodePathFor(key: String)                    = "%s/%s".format(CONFIGURATION_NODE, key)

  private[cluster] def actorIdToUuidsNodePathFor(actorId: String)               = "%s/%s".format(ACTOR_ID_TO_UUIDS_NODE, actorId.replace('.', '_'))
  private[cluster] def actorClassNameToUuidsNodePathFor(actorClassName: String) = "%s/%s".format(ACTOR_CLASS_TO_UUIDS_NODE, actorClassName)

  private[cluster] def actorLocationsNodePathFor(actorUuid: UUID)               = "%s/%s".format(ACTOR_LOCATIONS_NODE, actorUuid)
  private[cluster] def actorLocationsNodePathFor(actorUuid: UUID, node: NodeAddress) =
    "%s/%s/%s".format(ACTOR_LOCATIONS_NODE, actorUuid, node.nodeName)

  private[cluster] def actorsAtAddressNodePathFor(node: String)                 = "%s/%s".format(ACTORS_AT_ADDRESS_NODE, node)
  private[cluster] def actorAtAddressNodePathFor(node: String, uuid: UUID)      = "%s/%s/%s".format(ACTORS_AT_ADDRESS_NODE, node, uuid)

  private[cluster] def actorRegistryNodePathFor(actorUuid: UUID)                = "%s/%s".format(ACTOR_REGISTRY_NODE, actorUuid)
  private[cluster] def actorRegistryFormatNodePathFor(actorUuid: UUID)          = "%s/%s".format(actorRegistryNodePathFor(actorUuid), "format")
  private[cluster] def actorRegistryActorIdNodePathFor(actorUuid: UUID)         = "%s/%s".format(actorRegistryNodePathFor(actorUuid), "id")
  private[cluster] def actorRegistryActorClassNameNodePathFor(actorUuid: UUID)  = "%s/%s".format(actorRegistryNodePathFor(actorUuid), "class")
  private[cluster] def actorRegistryAddressNodePathFor(actorUuid: UUID): String = "%s/%s".format(actorRegistryNodePathFor(actorUuid), "address")
  private[cluster] def actorRegistryAddressNodePathFor(actorUuid: UUID, address: InetSocketAddress): String =
    "%s/%s:%s".format(actorRegistryAddressNodePathFor(actorUuid), address.getHostName, address.getPort)

  private[cluster] def initializeNode = {
    EventHandler.info(this, "Initializing cluster node [%s]".format(nodeAddress))
    createRootClusterNode
    val isLeader = joinLeaderElection
    if (isLeader) createNodeStructureIfNeeded
    registerListeners
    joinMembershipNode
    joinActorsAtAddressNode
    fetchMembershipChildrenNodes
    EventHandler.info(this, "Cluster node [%s] started successfully".format(nodeAddress))
  }

  private[cluster] def addressForNode(node: String): InetSocketAddress = {
    val address = zkClient.readData(membershipNodePathFor(node)).asInstanceOf[String]
    val tokenizer = new java.util.StringTokenizer(address, ":")
    tokenizer.nextToken // cluster name
    tokenizer.nextToken // node name
    val hostname = tokenizer.nextToken       // hostname
    val port     = tokenizer.nextToken.toInt // port
    new InetSocketAddress(hostname, port)
  }

  private def actorUuidsForActorAddress(actorAddress: ActorAddress): Array[UUID] = {
    val actorUuid      = actorAddress.actorUuid
    val actorId        = actorAddress.actorId
    val actorClassName = actorAddress.actorClassName
         if ((actorUuid     ne null)       && actorId    == EMPTY_STRING && actorClassName == EMPTY_STRING) Array(actorUuid)
    else if (actorId        != EMPTY_STRING && (actorUuid eq null)       && actorClassName == EMPTY_STRING) uuidsForActorId(actorId)
    else if (actorClassName != EMPTY_STRING && (actorUuid eq null)       && actorId        == EMPTY_STRING) uuidsForActorClassName(actorClassName)
    else throw new IllegalArgumentException("You need to pass in either 'actorUuid' or 'actorId' or 'actorClassName' and only one of them")
  } filter (_ ne null)

  /**
   * Returns a random set with replica connections of size 'replicationFactor'.
   * Default replicationFactor is 0, which returns the empty set.
   */
  private def replicaConnectionsForReplicationFactor(replicationFactor: Int = 0): Set[ActorRef] = {
    var replicas = HashSet.empty[ActorRef]
    if (replicationFactor < 1) return replicas

    connectToAllReplicas

    val numberOfReplicas = replicaConnections.size
    val replicaConnectionsAsArray = replicaConnections.toList map { case (node, (address, actorRef)) => actorRef } // the ActorRefs

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
  private def connectToAllReplicas = {
    membershipNodes foreach { node =>
      if (!replicaConnections.contains(node)) {
        val address = addressForNode(node)
        val clusterDaemon = Actor.remote.actorFor(RemoteClusterDaemon.ID, address.getHostName, address.getPort)
        replicaConnections.put(node, (address, clusterDaemon))
      }
    }
  }

  private[cluster] def joinMembershipNode = {
    nodeNameToAddress.put(nodeAddress.nodeName, remoteServerAddress)
    try {
      EventHandler.info(this,
        "Joining cluster as membership node [%s] on [%s]".format(nodeAddress, membershipNodePath))
      zkClient.createEphemeral(membershipNodePath, nodeAddress.toString)
    } catch {
      case e: ZkNodeExistsException =>
        val error = new ClusterException("Can't join the cluster. The node name [" + nodeAddress.nodeName + "] is already in by another node")
        EventHandler.error(error, this, "")
        throw error
    }
  }

  private[cluster] def joinActorsAtAddressNode =
    ignore[ZkNodeExistsException](zkClient.createPersistent(actorsAtAddressNodePathFor(nodeAddress.nodeName)))

  private[cluster] def joinLeaderElection: Boolean = {
    EventHandler.info(this, "Node [%s] is joining leader election".format(nodeAddress.nodeName))
    leaderLock.lock
  }

  private[cluster] def failOverConnections(from: InetSocketAddress, to: InetSocketAddress) {
    clusterActorRefs.values(from) foreach (_.failOver(from, to))
  }

  private[cluster] def migrateFromFailedNodes[T <: Actor](currentSetOfClusterNodes: List[String]) = {
    findFailedNodes(currentSetOfClusterNodes).foreach { failedNodeName =>

      val allNodes        = locallyCachedMembershipNodes.toList
      val myIndex         = allNodes.indexWhere(_.endsWith(nodeAddress.nodeName))
      val failedNodeIndex = allNodes.indexWhere(_ == failedNodeName)

      // Migrate to the successor of the failed node (using a sorted circular list of the node names)
      if ((failedNodeIndex == 0 && myIndex == locallyCachedMembershipNodes.size - 1) || // No leftmost successor exists, check the tail
          (failedNodeIndex == myIndex + 1)) { // Am I the leftmost successor?

        // Yes I am the node to migrate the actor to (can only be one in the cluster)
        val actorUuidsForFailedNode = zkClient.getChildren(actorsAtAddressNodePathFor(failedNodeName))
        EventHandler.debug(this,
          "Migrating actors from failed node [%s] to node [%s]: Actor UUIDs [%s]"
          .format(failedNodeName, nodeAddress.nodeName, actorUuidsForFailedNode))

        actorUuidsForFailedNode.foreach { actorUuid =>
          EventHandler.debug(this,
            "Cluster node [%s] has failed, migrating actor with UUID [%s] to [%s]"
            .format(failedNodeName, actorUuid, nodeAddress.nodeName))

          val actorAddress = ActorAddress(actorUuid = stringToUuid(actorUuid))
          migrateWithoutCheckingThatActorResidesOnItsHomeNode( // since the ephemeral node is already gone, so can't check
            NodeAddress(nodeAddress.clusterName, failedNodeName), nodeAddress, actorAddress)

          implicit val format: Format[T] = formatForActor(actorAddress)
          use(actorAddress) foreach { actor =>
            // FIXME remove ugly reflection when we have 1.0 final which has 'fromBinary(byte, homeAddress)(format)'
            //actor.homeAddress = remoteServerAddress
            val homeAddress = classOf[LocalActorRef].getDeclaredField("homeAddress")
            homeAddress.setAccessible(true)
            homeAddress.set(actor, Some(remoteServerAddress))

            remoteService.register(actorUuid, actor)
          }
        }

        // notify all available nodes that they should fail-over all connections from 'from' to 'to'
        val from    = nodeNameToAddress.get(failedNodeName)
        val to      = remoteServerAddress
        val command = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FAIL_OVER_CONNECTIONS)
          .setPayload(ByteString.copyFrom(Serializer.Java.toBinary((from, to))))
          .build
        membershipNodes foreach { node =>
          replicaConnections.get(node) foreach { case (_, connection) =>
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
    from: NodeAddress, to: NodeAddress, actorAddress: ActorAddress) {

    actorUuidsForActorAddress(actorAddress) map { uuid =>
      val actorAddress = ActorAddress(actorUuid = uuid)

      if (!isInUseOnNode(actorAddress, to)) {
        release(actorAddress)

        val newAddress = new InetSocketAddress(to.hostname, to.port)
        ignore[ZkNodeExistsException](zkClient.createPersistent(actorRegistryAddressNodePathFor(uuid)))
        ignore[ZkNodeExistsException](zkClient.createEphemeral(actorRegistryAddressNodePathFor(uuid, newAddress)))
        ignore[ZkNodeExistsException](zkClient.createEphemeral(actorLocationsNodePathFor(uuid, to)))
        ignore[ZkNodeExistsException](zkClient.createPersistent(actorAtAddressNodePathFor(nodeAddress.nodeName, uuid)))

        ignore[ZkNoNodeException](zkClient.delete(actorLocationsNodePathFor(uuid, from)))
        ignore[ZkNoNodeException](zkClient.delete(actorAtAddressNodePathFor(from.nodeName, uuid)))

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

  private def createRootClusterNode: Unit = ignore[ZkNodeExistsException] {
      zkClient.create(CLUSTER_NODE, null, CreateMode.PERSISTENT)
      EventHandler.info(this, "Created node [%s]".format(CLUSTER_NODE))
  }

  private def createNodeStructureIfNeeded = {
    baseNodes.foreach { path =>
      try {
        zkClient.create(path, null, CreateMode.PERSISTENT)
        EventHandler.debug(this, "Created node [%s]".format(path))
      } catch {
        case e: ZkNodeExistsException => {} // do nothing
        case e =>
          val error = new ClusterException(e.toString)
          EventHandler.error(error, this, "")
          throw error
      }
    }
  }

  private def registerListeners = {
    zkClient.subscribeStateChanges(stateListener)
    zkClient.subscribeChildChanges(MEMBERSHIP_NODE, membershipListener)
  }

  private def fetchMembershipChildrenNodes = {
    val membershipChildren = zkClient.getChildren(MEMBERSHIP_NODE)
    locallyCachedMembershipNodes.clear
    membershipChildren.iterator.foreach(locallyCachedMembershipNodes.add)
  }

  private def createMBean = {
    val clusterMBean = new StandardMBean(classOf[ClusterNodeMBean]) with ClusterNodeMBean {
      import Cluster._

      def start                                                 = self.start
      def stop                                                  = self.stop

      def disconnect                                            = self.disconnect
      def reconnect                                             = self.reconnect
      def resign                                                = self.resign

      def isConnected                                           = self.isConnected.isOn

      def getRemoteServerHostname                               = self.nodeAddress.hostname
      def getRemoteServerPort                                   = self.nodeAddress.port

      def getNodeName                                           = self.nodeAddress.nodeName
      def getClusterName                                        = self.nodeAddress.clusterName
      def getZooKeeperServerAddresses                           = self.zkServerAddresses

      def getMemberNodes                                        = self.locallyCachedMembershipNodes.iterator.map(_.toString).toArray
      def getLeader                                             = self.leader.toString

      def getUuidsForActorsInUse                                = self.uuidsForActorsInUse.map(_.toString).toArray
      def getIdsForActorsInUse                                  = self.idsForActorsInUse.map(_.toString).toArray
      def getClassNamesForActorsInUse                           = self.classNamesForActorsInUse.map(_.toString).toArray

      def getUuidsForClusteredActors                            = self.uuidsForClusteredActors.map(_.toString).toArray
      def getIdsForClusteredActors                              = self.idsForClusteredActors.map(_.toString).toArray
      def getClassNamesForClusteredActors                       = self.classNamesForClusteredActors.map(_.toString).toArray

      def getNodesForActorInUseWithUuid(uuid: String)           = self.nodesForActorsInUseWithUuid(stringToUuid(uuid))
      def getNodesForActorInUseWithId(id: String)               = self.nodesForActorsInUseWithId(id)
      def getNodesForActorInUseWithClassName(className: String) = self.nodesForActorsInUseWithClassName(className)

      def getUuidsForActorsInUseOnNode(nodeName: String)        = self.uuidsForActorsInUseOnNode(nodeName).map(_.toString).toArray
      def getIdsForActorsInUseOnNode(nodeName: String)          = self.idsForActorsInUseOnNode(nodeName).map(_.toString).toArray
      def getClassNamesForActorsInUseOnNode(nodeName: String)   = self.classNamesForActorsInUseOnNode(nodeName).map(_.toString).toArray

      def setConfigElement(key: String, value: String)          = self.setConfigElement(key, value.getBytes("UTF-8"))
      def getConfigElement(key: String)                         = new String(self.getConfigElement(key), "UTF-8")
      def removeConfigElement(key: String)                      = self.removeConfigElement(key)
      def getConfigElementKeys                                  = self.getConfigElementKeys.toArray
    }

    JMX.register(clusterJmxObjectName, clusterMBean)
    Monitoring.registerLocalMBean(clusterJmxObjectName, clusterMBean)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MembershipChildListener(self: ClusterNode) extends IZkChildListener with ErrorHandler {
  def handleChildChange(parentPath: String, currentChilds: JList[String]) = withErrorHandler {
    if (currentChilds ne null) {
      val childList = currentChilds.toList
      if (!childList.isEmpty) EventHandler.debug(this,
        "MembershipChildListener at [%s] has children [%s]"
        .format(self.nodeAddress.nodeName, childList.mkString(" ")))
      self.findNewlyConnectedMembershipNodes(childList) foreach { name =>
        self.nodeNameToAddress.put(name, self.addressForNode(name)) // update 'nodename-address' map
        self.publish(Cluster.NodeConnected(name))
      }

      self.findNewlyDisconnectedMembershipNodes(childList) foreach { name =>
        self.nodeNameToAddress.remove(name) // update 'nodename-address' map
        self.publish(Cluster.NodeDisconnected(name))
      }

      self.locallyCachedMembershipNodes.clear
      childList.foreach(self.locallyCachedMembershipNodes.add)
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class StateListener(self: ClusterNode) extends IZkStateListener {
  def handleStateChanged(state: KeeperState) = state match {

    case KeeperState.SyncConnected =>
      EventHandler.debug(this, "Cluster node [%s] - Connected".format(self.nodeAddress))
      self.publish(Cluster.ThisNode.Connected)

    case KeeperState.Disconnected =>
      EventHandler.debug(this, "Cluster node [%s] - Disconnected".format(self.nodeAddress))
      self.publish(Cluster.ThisNode.Disconnected)

    case KeeperState.Expired =>
      EventHandler.debug(this, "Cluster node [%s] - Expired".format(self.nodeAddress))
      self.publish(Cluster.ThisNode.Expired)
  }

  /**
   * Re-initialize after the zookeeper session has expired and a new session has been created.
   */
  def handleNewSession = {
    EventHandler.debug(this, "Session expired re-initializing node [%s]".format(self.nodeAddress))
    self.initializeNode
    self.publish(Cluster.NewSession)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ErrorHandler {
  def withErrorHandler[T](body: => T) = {
    try {
      body
    } catch {
      case e: org.I0Itec.zkclient.exception.ZkInterruptedException => { /* ignore */ }
      case e: Throwable =>
        EventHandler.error(e, this, e.toString)
        throw e
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteClusterDaemon {
  val ID = "akka:cloud:cluster:daemon"

  // FIXME configure functionServerDispatcher to what?
  val functionServerDispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher("akka:cloud:cluster:function:server").build
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClusterDaemon(cluster: ClusterNode) extends Actor {
  import RemoteClusterDaemon._
  import Cluster._

  self.id = ID
  self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)

  def receive: Receive = {
    case message: RemoteDaemonMessageProtocol =>
      EventHandler.debug(this, "Received command to RemoteClusterDaemon [%s]".format(message))
      message.getMessageType match {

        case USE =>
          if (message.hasActorUuid) {
            val uuid = uuidProtocolToUuid(message.getActorUuid)
            val address = ActorAddress(actorUuid = uuid)
            implicit val format: Format[Actor] = cluster formatForActor address
            val actors = cluster use address
          } else if (message.hasActorId) {
            val id = message.getActorId
            val address = ActorAddress(actorId = id)
            implicit val format: Format[Actor] = cluster formatForActor address
            val actors = cluster use address
          } else if (message.hasActorClassName) {
            val actorClassName = message.getActorClassName
            val address = ActorAddress(actorClassName = actorClassName)
            implicit val format: Format[Actor] = cluster formatForActor address
            val actors = cluster use address
          } else EventHandler.warning(this,
            "None of 'actorUuid', 'actorId' or 'actorClassName' is specified, ignoring remote cluster daemon command [%s]".format(message))

        case RELEASE =>
               if (message.hasActorUuid)      { cluster release ActorAddress(actorUuid      = uuidProtocolToUuid(message.getActorUuid)) }
          else if (message.hasActorId)        { cluster release ActorAddress(actorId        = message.getActorId) }
          else if (message.hasActorClassName) { cluster release ActorAddress(actorClassName = message.getActorClassName) }
          else EventHandler.warning(this,
            "None of 'actorUuid', 'actorId' or 'actorClassName' is specified, ignoring remote cluster daemon command [%s]".format(message))

        case START                 => cluster.start

        case STOP                  => cluster.stop

        case DISCONNECT            => cluster.disconnect

        case RECONNECT             => cluster.reconnect

        case RESIGN                => cluster.resign

        case FAIL_OVER_CONNECTIONS =>
          val (from, to) = payloadFor(message, classOf[(InetSocketAddress, InetSocketAddress)])
          cluster.failOverConnections(from, to)

        case FUNCTION_FUN0_UNIT =>
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher
            def receive = {
              case f: Function0[Unit] => try { f() } finally { self.stop }
            }
          }).start ! payloadFor(message, classOf[Function0[Unit]])

        case FUNCTION_FUN0_ANY =>
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher
            def receive = {
              case f: Function0[Any] => try { self.reply(f()) } finally { self.stop }
            }
          }).start forward payloadFor(message, classOf[Function0[Any]])

        case FUNCTION_FUN1_ARG_UNIT =>
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher
            def receive = {
              case t: Tuple2[Function1[Any, Unit], Any] => try { t._1(t._2) } finally { self.stop }
            }
          }).start ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])

        case FUNCTION_FUN1_ARG_ANY =>
          actorOf(new Actor() {
            self.dispatcher = functionServerDispatcher
            def receive = {
              case t: Tuple2[Function1[Any, Any], Any] => try { self.reply(t._1(t._2)) } finally { self.stop }
            }
          }).start forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
      }

    case unknown => EventHandler.warning(this, "Unknown message [%s]".format(unknown))
  }

  private def payloadFor[T](message: RemoteDaemonMessageProtocol, clazz: Class[T]): T = {
    Serializer.Java.fromBinary(message.getPayload.toByteArray, Some(clazz)).asInstanceOf[T]
  }
}
