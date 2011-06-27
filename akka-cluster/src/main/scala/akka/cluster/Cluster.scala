
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

import java.util.{ List ⇒ JList }
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
import Status._
import DeploymentConfig.{ ReplicationScheme, ReplicationStrategy, Transient, WriteThrough, WriteBehind }

import akka.event.EventHandler
import akka.dispatch.{ Dispatchers, Future }
import akka.remoteinterface._
import akka.routing.RouterType

import akka.config.{ Config, Supervision }
import Supervision._
import Config._

import akka.serialization.{ Serialization, Serializer, Compression }
import Compression.LZF
import akka.AkkaException

import akka.cluster.zookeeper._
import akka.cluster.ChangeListener._

import com.eaio.uuid.UUID

import com.google.protobuf.ByteString

// FIXME add watch for each node that when the entry for the node is removed then the node shuts itself down
// FIXME Provisioning data in ZK (file names etc) and files in S3 and on disk

/**
 * JMX MBean for the cluster service.
 *
 * FIXME revisit the methods in this MBean interface, they are not up to date with new cluster API
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

  // config options
  val name = Config.clusterName
  val zooKeeperServers = config.getString("akka.cluster.zookeeper-server-addresses", "localhost:2181")
  val remoteServerPort = config.getInt("akka.cluster.remote-server-port", 2552)
  val sessionTimeout = Duration(config.getInt("akka.cluster.session-timeout", 60), TIME_UNIT).toMillis.toInt
  val connectionTimeout = Duration(config.getInt("akka.cluster.connection-timeout", 60), TIME_UNIT).toMillis.toInt
  val maxTimeToWaitUntilConnected = Duration(config.getInt("akka.cluster.max-time-to-wait-until-connected", 30), TIME_UNIT).toMillis.toInt
  val shouldCompressData = config.getBool("akka.cluster.use-compression", false)
  val enableJMX = config.getBool("akka.enable-jmx", true)
  val remoteDaemonAckTimeout = Duration(config.getInt("akka.cluster.remote-daemon-ack-timeout", 30), TIME_UNIT).toMillis.toInt
  val excludeRefNodeInReplicaSet = config.getBool("akka.cluster.exclude-ref-node-in-replica-set", true)

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
  val nodeAddress = NodeAddress(name, nodename)

  /**
   * The reference to the running ClusterNode.
   */
  val node = {
    if (nodeAddress eq null) throw new IllegalArgumentException("NodeAddress can't be null")
    new DefaultClusterNode(nodeAddress, hostname, port, zooKeeperServers, defaultSerializer)
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
  def newZkClient(): AkkaZkClient = new AkkaZkClient(zooKeeperServers, sessionTimeout, connectionTimeout, defaultSerializer)

  def createQueue(rootPath: String, blocking: Boolean = true) = new ZooKeeperQueue(node.zkClient, rootPath, blocking)

  def barrier(name: String, count: Int): ZooKeeperBarrier =
    ZooKeeperBarrier(node.zkClient, node.nodeAddress.clusterName, name, node.nodeAddress.nodeName, count)

  def barrier(name: String, count: Int, timeout: Duration): ZooKeeperBarrier =
    ZooKeeperBarrier(node.zkClient, node.nodeAddress.clusterName, name, node.nodeAddress.nodeName, count, timeout)

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

  def uuidProtocolToUuid(uuid: UuidProtocol): UUID = new UUID(uuid.getHigh, uuid.getLow)

  def uuidToUuidProtocol(uuid: UUID): UuidProtocol =
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
class DefaultClusterNode private[akka] (
  val nodeAddress: NodeAddress,
  val hostname: String = Config.hostname,
  val port: Int = Config.remoteServerPort,
  val zkServerAddresses: String,
  val serializer: ZkSerializer) extends ErrorHandler with ClusterNode {
  self ⇒

  if ((hostname eq null) || hostname == "") throw new NullPointerException("Host name must not be null or empty string")
  if (port < 1) throw new NullPointerException("Port can not be negative")
  if (nodeAddress eq null) throw new IllegalArgumentException("'nodeAddress' can not be 'null'")

  val clusterJmxObjectName = JMX.nameFor(hostname, "monitoring", "cluster")

  import Cluster._

  private[cluster] lazy val remoteClientLifeCycleListener = localActorOf(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) ⇒ client.shutdownClientModule()
      case RemoteClientDisconnected(client, address) ⇒ client.shutdownClientModule()
      case _                                         ⇒ //ignore other
    }
  }, "akka.cluster.RemoteClientLifeCycleListener").start()

  private[cluster] lazy val remoteDaemon = localActorOf(new RemoteClusterDaemon(this), RemoteClusterDaemon.ADDRESS).start()

  private[cluster] lazy val remoteDaemonSupervisor = Supervisor(
    SupervisorConfig(
      OneForOneStrategy(List(classOf[Exception]), Int.MaxValue, Int.MaxValue), // is infinite restart what we want?
      Supervise(
        remoteDaemon,
        Permanent)
        :: Nil))

  lazy val remoteService: RemoteSupport = {
    val remote = new akka.remote.netty.NettyRemoteSupport
    remote.start(hostname, port)
    remote.register(RemoteClusterDaemon.ADDRESS, remoteDaemon)
    remote.addListener(remoteClientLifeCycleListener)
    remote
  }

  lazy val remoteServerAddress: InetSocketAddress = remoteService.address

  // static nodes
  val CLUSTER_PATH = "/" + nodeAddress.clusterName
  val MEMBERSHIP_PATH = CLUSTER_PATH + "/members"
  val CONFIGURATION_PATH = CLUSTER_PATH + "/config"
  val PROVISIONING_PATH = CLUSTER_PATH + "/provisioning"
  val ACTOR_REGISTRY_PATH = CLUSTER_PATH + "/actor-registry"
  val ACTOR_LOCATIONS_PATH = CLUSTER_PATH + "/actor-locations"
  val ACTOR_ADDRESS_TO_UUIDS_PATH = CLUSTER_PATH + "/actor-address-to-uuids"
  val ACTORS_AT_PATH_PATH = CLUSTER_PATH + "/actors-at-address"
  val basePaths = List(
    CLUSTER_PATH,
    MEMBERSHIP_PATH,
    ACTOR_REGISTRY_PATH,
    ACTOR_LOCATIONS_PATH,
    ACTORS_AT_PATH_PATH,
    ACTOR_ADDRESS_TO_UUIDS_PATH,
    CONFIGURATION_PATH,
    PROVISIONING_PATH)

  val LEADER_ELECTION_PATH = CLUSTER_PATH + "/leader" // should NOT be part of 'basePaths' only used by 'leaderLock'

  private val membershipNodePath = membershipPathFor(nodeAddress.nodeName)

  def membershipNodes: Array[String] = locallyCachedMembershipNodes.toList.toArray.asInstanceOf[Array[String]]

  private[akka] val nodeConnections: ConcurrentMap[String, Tuple2[InetSocketAddress, ActorRef]] =
    new ConcurrentHashMap[String, Tuple2[InetSocketAddress, ActorRef]]

  // zookeeper listeners
  private val stateListener = new StateListener(this)
  private val membershipListener = new MembershipChildListener(this)

  // cluster node listeners
  private val changeListeners = new CopyOnWriteArrayList[ChangeListener]()

  // Address -> ClusterActorRef
  private val clusterActorRefs = new Index[InetSocketAddress, ClusterActorRef]

  // resources
  lazy private[cluster] val zkClient = new AkkaZkClient(zkServerAddresses, sessionTimeout, connectionTimeout, serializer)

  lazy private[cluster] val leaderElectionCallback = new LockListener {
    override def lockAcquired() {
      EventHandler.info(this, "Node [%s] is the new leader".format(self.nodeAddress.nodeName))
      self.publish(NewLeader(self.nodeAddress.nodeName))
    }

    override def lockReleased() {
      EventHandler.info(this, "Node [%s] is *NOT* the leader anymore".format(self.nodeAddress.nodeName))
    }
  }

  lazy private[cluster] val leaderLock = new WriteLock(
    zkClient.connection.getZookeeper,
    LEADER_ELECTION_PATH, null,
    leaderElectionCallback)

  if (enableJMX) createMBean

  // =======================================
  // Node
  // =======================================

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

      nodeConnections.toList.foreach({
        case (_, (address, _)) ⇒
          Actor.remote.shutdownClientConnection(address) // shut down client connections
      })

      remoteService.shutdown() // shutdown server

      remoteClientLifeCycleListener.stop()
      remoteDaemon.stop()

      // for monitoring remote listener
      registry.local.actors.filter(remoteService.hasListener).foreach(_.stop())

      nodeConnections.clear()

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
  def register(listener: ChangeListener): ClusterNode = {
    changeListeners.add(listener)
    this
  }

  private[cluster] def publish(change: ChangeNotification) {
    changeListeners.iterator.foreach(_.notify(change, this))
  }

  // =======================================
  // Leader
  // =======================================

  /**
   * Returns the name of the current leader lock.
   */
  def leader: String = leaderLock.getId

  /**
   * Returns true if 'this' node is the current leader.
   */
  def isLeader: Boolean = leaderLock.isOwner

  /**
   * Explicitly resign from being a leader. If this node is not a leader then this operation is a no-op.
   */
  def resign() {
    if (isLeader) leaderLock.unlock()
  }

  // =======================================
  // Actor
  // =======================================

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, 0, Transient, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, 0, replicationScheme, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, replicationFactor, Transient, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, replicationFactor, replicationScheme, false, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, 0, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, 0, replicationScheme, serializeMailbox, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, replicationFactor, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor of a specific type. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store[T <: Actor](address: String, actorClass: Class[T], replicationFactor: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(Actor.actorOf(actorClass, address).start, replicationFactor, replicationScheme, serializeMailbox, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, serializer: Serializer): ClusterNode =
    store(actorRef, 0, Transient, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorRef, 0, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(actorRef, 0, replicationScheme, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, serializer: Serializer): ClusterNode =
    store(actorRef, replicationFactor, Transient, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, replicationScheme: ReplicationScheme, serializer: Serializer): ClusterNode =
    store(actorRef, replicationFactor, replicationScheme, false, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorRef, replicationFactor, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(actorRef: ActorRef, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: Serializer): ClusterNode =
    store(actorRef, 0, replicationScheme, serializeMailbox, serializer)

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, replicationScheme: ReplicationScheme, serializeMailbox: Boolean, serializer: AnyRef): ClusterNode =
    store(actorRef, replicationFactor, replicationScheme, serializeMailbox, serializer.asInstanceOf[Serializer])

  /**
   * Needed to have reflection through structural typing work.
   */
  def store(actorRef: ActorRef, replicationFactor: Int, serializeMailbox: Boolean, serializer: AnyRef): ClusterNode =
    store(actorRef, replicationFactor, Transient, serializeMailbox, serializer)

  /**
   * Clusters an actor with UUID. If the actor is already clustered then the clustered version will be updated
   * with the actor passed in as argument. You can use this to save off snapshots of the actor to a highly
   * available durable store.
   */
  def store(
    actorRef: ActorRef,
    replicationFactor: Int,
    replicationScheme: ReplicationScheme,
    serializeMailbox: Boolean,
    serializer: Serializer): ClusterNode = if (isConnected.isOn) {

    import akka.serialization.ActorSerialization._

    if (!actorRef.isInstanceOf[LocalActorRef]) throw new IllegalArgumentException(
      "'actorRef' must be an instance of 'LocalActorRef' [" + actorRef.getClass.getName + "]")

    val serializerClassName = serializer.getClass.getName

    val uuid = actorRef.uuid
    EventHandler.debug(this,
      "Storing actor [%s] with UUID [%s] in cluster".format(actorRef.address, uuid))

    val actorBytes =
      if (shouldCompressData) LZF.compress(toBinary(actorRef, serializeMailbox, replicationScheme))
      else toBinary(actorRef, serializeMailbox, replicationScheme)

    val actorRegistryPath = actorRegistryPathFor(uuid)

    // create UUID -> Array[Byte] for actor registry
    try {
      zkClient.writeData(actorRegistryPath, actorBytes) // FIXME Store actor bytes in Data Grid not ZooKeeper
    } catch {
      case e: ZkNoNodeException ⇒ // if not stored yet, store the actor
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

        // create UUID -> serializer class name registry
        try {
          zkClient.createPersistent(actorRegistrySerializerPathFor(uuid), serializerClassName)
        } catch {
          case e: ZkNodeExistsException ⇒ zkClient.writeData(actorRegistrySerializerPathFor(uuid), serializerClassName)
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

        // create NODE NAME -> UUID registry
        ignore[ZkNodeExistsException](zkClient.createPersistent(actorAtNodePathFor(nodeAddress.nodeName, uuid)))
    }

    import RemoteClusterDaemon._
    val command = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorUuid(uuidToUuidProtocol(uuid))
      .build

    nodeConnectionsForReplicationFactor(replicationFactor) foreach { connection ⇒ sendCommandToReplica(connection, command, async = false) }

    this
  } else throw new ClusterException("Not connected to cluster")

  /**
   * Removes actor from the cluster.
   */
  def remove(actorRef: ActorRef) {
    remove(actorRef.uuid)
  }

  /**
   * Removes actor with uuid from the cluster.
   */
  def remove(uuid: UUID) {
    releaseActorOnAllNodes(uuid)

    locallyCheckedOutActors.remove(uuid)

    // warning: ordering matters here
    // FIXME remove ADDRESS to UUID mapping?
    actorAddressForUuid(uuid) foreach (address ⇒ ignore[ZkNoNodeException](zkClient.deleteRecursive(actorAddressToUuidsPathFor(address))))
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
        "Removing actor(s) with address [%s] from cluster".format(address))
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
  def use[T <: Actor](actorAddress: String): Option[ActorRef] = use(actorAddress, serializerForActor(actorAddress))

  /**
   * Checks out an actor for use on this node, e.g. checked out as a 'LocalActorRef' but it makes it available
   * for remote access through lookup by its UUID.
   */
  def use[T <: Actor](actorAddress: String, serializer: Serializer): Option[ActorRef] = if (isConnected.isOn) {

    import akka.serialization.ActorSerialization._

    actorUuidsForActorAddress(actorAddress) map { uuid ⇒

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
            case e: KeeperException.NoNodeException ⇒ Right(e)
          }
        }
      }) match {
        case Left(bytes) ⇒
          locallyCheckedOutActors += (uuid -> bytes)
          val actor = fromBinary[T](bytes, remoteServerAddress)
          EventHandler.debug(this,
            "Checking out actor [%s] to be used on node [%s] as local actor"
              .format(actor, nodeAddress.nodeName))
          actor.start()
          actor
        case Right(exception) ⇒ throw exception
      }
    } headOption // FIXME should not be an array at all coming here but an Option[ActorRef]
  } else None

  /**
   * Using (checking out) all actors with a specific UUID on all nodes in the cluster.
   */
  def useActorOnAllNodes(uuid: UUID) {
    isConnected ifOn {
      EventHandler.debug(this,
        "Using (checking out) all actors with UUID [%s] on all nodes in cluster".format(uuid))

      connectToAllNewlyArrivedMembershipNodesInCluster()

      val command = RemoteDaemonMessageProtocol.newBuilder
        .setMessageType(USE)
        .setActorUuid(uuidToUuidProtocol(uuid))
        .build

      membershipNodes foreach { node ⇒
        nodeConnections.get(node) foreach {
          case (_, connection) ⇒ sendCommandToReplica(connection, command, async = false)
        }
      }
    }
  }

  /**
   * Using (checking out) specific UUID on a specific node.
   */
  def useActorOnNode(node: String, uuid: UUID) {
    isConnected ifOn {
      connectToAllNewlyArrivedMembershipNodesInCluster()
      nodeConnections.get(node) foreach {
        case (_, connection) ⇒
          val command = RemoteDaemonMessageProtocol.newBuilder
            .setMessageType(USE)
            .setActorUuid(uuidToUuidProtocol(uuid))
            .build
          sendCommandToReplica(connection, command, async = false)
      }
    }
  }

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorRef: ActorRef) {
    release(actorRef.address)
  }

  /**
   * Checks in an actor after done using it on this node.
   */
  def release(actorAddress: String) {

    // FIXME 'Cluster.release' needs to notify all existing ClusterActorRef's that are using the instance that it is no longer available. Then what to do? Should we even remove this method?

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
  private[akka] def releaseActorOnAllNodes(uuid: UUID) {
    isConnected ifOn {
      EventHandler.debug(this,
        "Releasing (checking in) all actors with UUID [%s] on all nodes in cluster".format(uuid))

      connectToAllNewlyArrivedMembershipNodesInCluster()

      val command = RemoteDaemonMessageProtocol.newBuilder
        .setMessageType(RELEASE)
        .setActorUuid(uuidToUuidProtocol(uuid))
        .build

      nodesForActorsInUseWithUuid(uuid) foreach { node ⇒
        nodeConnections.get(node) foreach {
          case (_, connection) ⇒ sendCommandToReplica(connection, command, async = true)
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
      "Checking out cluster actor ref with address [%s] and router [%s] on [%s] connected to [\n\t%s]"
        .format(actorAddress, router, remoteServerAddress, addresses.map(_._2).mkString("\n\t")))

    val actorRef = Router newRouter (router, addresses, actorAddress, Actor.TIMEOUT)
    addresses foreach { case (_, address) ⇒ clusterActorRefs.put(address, actorRef) }
    actorRef.start()

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
  private[akka] def uuidsForActorsInUse: Array[UUID] = uuidsForActorsInUseOnNode(nodeAddress.nodeName)

  /**
   * Returns the addresses of all actors checked out on this node.
   */
  def addressesForActorsInUse: Array[String] = actorAddressForUuids(uuidsForActorsInUse)

  /**
   * Returns the UUIDs of all actors registered in this cluster.
   */
  private[akka] def uuidsForClusteredActors: Array[UUID] = if (isConnected.isOn) {
    zkClient.getChildren(ACTOR_REGISTRY_PATH).toList.map(new UUID(_)).toArray.asInstanceOf[Array[UUID]]
  } else Array.empty[UUID]

  /**
   * Returns the addresses of all actors registered in this cluster.
   */
  def addressesForClusteredActors: Array[String] = actorAddressForUuids(uuidsForClusteredActors)

  /**
   * Returns the actor id for the actor with a specific UUID.
   */
  private[akka] def actorAddressForUuid(uuid: UUID): Option[String] = if (isConnected.isOn) {
    try {
      Some(zkClient.readData(actorRegistryActorAddressPathFor(uuid)).asInstanceOf[String])
    } catch {
      case e: ZkNoNodeException ⇒ None
    }
  } else None

  /**
   * Returns the actor ids for all the actors with a specific UUID.
   */
  private[akka] def actorAddressForUuids(uuids: Array[UUID]): Array[String] =
    uuids map (actorAddressForUuid(_)) filter (_.isDefined) map (_.get)

  /**
   * Returns the actor UUIDs for actor ID.
   */
  private[akka] def uuidsForActorAddress(actorAddress: String): Array[UUID] = if (isConnected.isOn) {
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
  private[akka] def nodesForActorsInUseWithUuid(uuid: UUID): Array[String] = if (isConnected.isOn) {
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
  private[akka] def uuidsForActorsInUseOnNode(nodeName: String): Array[UUID] = if (isConnected.isOn) {
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
   * Returns Serializer for actor with specific address.
   */
  def serializerForActor(actorAddress: String): Serializer = {
    // FIXME should only be 1 single class name per actor address - FIX IT

    val serializerClassNames = actorUuidsForActorAddress(actorAddress) map { uuid ⇒
      try {
        Some(zkClient.readData(actorRegistrySerializerPathFor(uuid), new Stat).asInstanceOf[String])
      } catch {
        case e: ZkNoNodeException ⇒ None
      }
    } filter (_.isDefined) map (_.get)

    if (serializerClassNames.isEmpty) throw new IllegalStateException("No serializer found for actor with address [%s]".format(actorAddress))
    if (serializerClassNames.forall(_ == serializerClassNames.head) == false)
      throw new IllegalStateException("Multiple serializers found for actor with address [%s]".format(actorAddress))

    val serializerClassName = serializerClassNames.head
    ReflectiveAccess.getClassFor(serializerClassName) match { // FIXME need to pass in a user provide class loader? Now using default in ReflectiveAccess.
      case Right(clazz) ⇒ clazz.newInstance.asInstanceOf[Serializer]
      case Left(error) ⇒
        EventHandler.error(error, this, "Could not load serializer class [%s] due to: %s".format(serializerClassName, error.toString))
        throw error
    }
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
    Serialization.serialize(f) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN0_UNIT)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        nodeConnectionsForReplicationFactor(replicationFactor) foreach (_ ! message)
    }
  }

  /**
   * Send a function 'Function0[Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument).
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function0[Any], replicationFactor: Int): List[Future[Any]] = {
    Serialization.serialize(f) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN0_ANY)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        val results = nodeConnectionsForReplicationFactor(replicationFactor) map (_ ? message)
        results.toList.asInstanceOf[List[Future[Any]]]
    }
  }

  /**
   * Send a function 'Function1[Any, Unit]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   */
  def send(f: Function1[Any, Unit], arg: Any, replicationFactor: Int) {
    Serialization.serialize((f, arg)) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN1_ARG_UNIT)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        nodeConnectionsForReplicationFactor(replicationFactor) foreach (_ ! message)
    }
  }

  /**
   * Send a function 'Function1[Any, Any]' to be invoked on a random number of nodes (defined by 'replicationFactor' argument)
   * with the argument speficied.
   * Returns an 'Array' with all the 'Future's from the computation.
   */
  def send(f: Function1[Any, Any], arg: Any, replicationFactor: Int): List[Future[Any]] = {
    Serialization.serialize((f, arg)) match {
      case Left(error) ⇒ throw error
      case Right(bytes) ⇒
        val message = RemoteDaemonMessageProtocol.newBuilder
          .setMessageType(FUNCTION_FUN1_ARG_ANY)
          .setPayload(ByteString.copyFrom(bytes))
          .build
        val results = nodeConnectionsForReplicationFactor(replicationFactor) map (_ ? message)
        results.toList.asInstanceOf[List[Future[Any]]]
    }
  }

  // =======================================
  // Config
  // =======================================

  /**
   * Stores a configuration element under a specific key.
   * If the key already exists then it will be overwritten.
   */
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
   * Returns <code>Some(element)</code> if it exists else <code>None</code>
   */
  def getConfigElement(key: String): Option[Array[Byte]] = try {
    Some(zkClient.connection.readData(configurationPathFor(key), new Stat, true))
  } catch {
    case e: KeeperException.NoNodeException ⇒ None
  }

  /**
   * Removes configuration element for a specific key.
   * Does nothing if the key does not exist.
   */
  def removeConfigElement(key: String) {
    ignore[ZkNoNodeException] {
      EventHandler.debug(this,
        "Removing config element with key [%s] from cluster registry".format(key))
      zkClient.deleteRecursive(configurationPathFor(key))
    }
  }

  /**
   * Returns a list with all config element keys.
   */
  def getConfigElementKeys: Array[String] = zkClient.getChildren(CONFIGURATION_PATH).toList.toArray.asInstanceOf[Array[String]]

  // =======================================
  // Private
  // =======================================

  private def sendCommandToReplica(connection: ActorRef, command: RemoteDaemonMessageProtocol, async: Boolean = true) {
    if (async) {
      connection ! command
    } else {
      (connection ? (command, remoteDaemonAckTimeout)).as[Status] match {

        case Some(Success) ⇒
          EventHandler.debug(this, "Replica for [%s] successfully created".format(connection.address))

        case Some(Failure(cause)) ⇒
          EventHandler.error(cause, this, cause.toString)
          throw cause

        case None ⇒
          val error = new ClusterException(
            "Operation to instantiate replicas throughout the cluster timed out")
          EventHandler.error(error, this, error.toString)
          throw error
      }
    }
  }

  private[cluster] def membershipPathFor(node: String) = "%s/%s".format(MEMBERSHIP_PATH, node)

  private[cluster] def configurationPathFor(key: String) = "%s/%s".format(CONFIGURATION_PATH, key)

  private[cluster] def actorAddressToUuidsPathFor(actorAddress: String) = "%s/%s".format(ACTOR_ADDRESS_TO_UUIDS_PATH, actorAddress.replace('.', '_'))

  private[cluster] def actorLocationsPathFor(uuid: UUID) = "%s/%s".format(ACTOR_LOCATIONS_PATH, uuid)

  private[cluster] def actorLocationsPathFor(uuid: UUID, node: NodeAddress) =
    "%s/%s/%s".format(ACTOR_LOCATIONS_PATH, uuid, node.nodeName)

  private[cluster] def actorsAtNodePathFor(node: String) = "%s/%s".format(ACTORS_AT_PATH_PATH, node)

  private[cluster] def actorAtNodePathFor(node: String, uuid: UUID) = "%s/%s/%s".format(ACTORS_AT_PATH_PATH, node, uuid)

  private[cluster] def actorRegistryPathFor(uuid: UUID) = "%s/%s".format(ACTOR_REGISTRY_PATH, uuid)

  private[cluster] def actorRegistrySerializerPathFor(uuid: UUID) = "%s/%s".format(actorRegistryPathFor(uuid), "serializer")

  private[cluster] def actorRegistryActorAddressPathFor(uuid: UUID) = "%s/%s".format(actorRegistryPathFor(uuid), "address")

  private[cluster] def actorRegistryNodePathFor(uuid: UUID): String = "%s/%s".format(actorRegistryPathFor(uuid), "node")

  private[cluster] def actorRegistryNodePathFor(uuid: UUID, address: InetSocketAddress): String =
    "%s/%s:%s".format(actorRegistryNodePathFor(uuid), address.getHostName, address.getPort)

  private[cluster] def initializeNode() {
    EventHandler.info(this,
      ("\nCreating cluster node with" +
        "\n\tcluster name = [%s]" +
        "\n\tnode name = [%s]" +
        "\n\tport = [%s]" +
        "\n\tzookeeper server addresses = [%s]" +
        "\n\tserializer = [%s]")
        .format(nodeAddress.clusterName, nodeAddress.nodeName, port, zkServerAddresses, serializer))
    EventHandler.info(this, "Starting up remote server [%s]".format(remoteServerAddress.toString))
    createRootClusterNode()
    val isLeader = joinLeaderElection()
    if (isLeader) createNodeStructureIfNeeded()
    registerListeners()
    joinCluster()
    createActorsAtAddressPath()
    fetchMembershipNodes()
    EventHandler.info(this, "Cluster node [%s] started successfully".format(nodeAddress))
  }

  private def actorUuidsForActorAddress(actorAddress: String): Array[UUID] =
    uuidsForActorAddress(actorAddress) filter (_ ne null)

  /**
   * Returns a random set with replica connections of size 'replicationFactor'.
   * Default replicationFactor is 0, which returns the empty set.
   */
  private def nodeConnectionsForReplicationFactor(replicationFactor: Int = 0): Set[ActorRef] = {
    var replicas = HashSet.empty[ActorRef]
    if (replicationFactor < 1) return replicas

    connectToAllNewlyArrivedMembershipNodesInCluster()

    val numberOfReplicas = nodeConnections.size
    val nodeConnectionsAsArray = nodeConnections.toList map {
      case (node, (address, actorRef)) ⇒ actorRef
    } // the ActorRefs

    if (numberOfReplicas < replicationFactor) {
      throw new IllegalArgumentException(
        "Replication factor [" + replicationFactor +
          "] is greater than the number of available nodes [" + numberOfReplicas + "]")
    } else if (numberOfReplicas == replicationFactor) {
      replicas = replicas ++ nodeConnectionsAsArray
    } else {
      val random = new java.util.Random(System.currentTimeMillis)
      while (replicas.size < replicationFactor) {
        val index = random.nextInt(numberOfReplicas)
        replicas = replicas + nodeConnectionsAsArray(index)
      }
    }
    replicas
  }

  /**
   * Connect to all available replicas unless already connected).
   */
  private def connectToAllNewlyArrivedMembershipNodesInCluster(currentSetOfClusterNodes: Traversable[String] = membershipNodes) {
    currentSetOfClusterNodes foreach { node ⇒
      if ((node != Config.nodename)) { // no replica on the "home" node of the ref
        if (!nodeConnections.contains(node)) { // only connect to each replica once
          val addressOption = remoteSocketAddressForNode(node)
          if (addressOption.isDefined) {
            val address = addressOption.get
            EventHandler.debug(this,
              "Setting up connection to node with nodename [%s] and address [%s]".format(node, address))
            val clusterDaemon = Actor.remote.actorFor(RemoteClusterDaemon.ADDRESS, address.getHostName, address.getPort)
            nodeConnections.put(node, (address, clusterDaemon))
          }
        }
      }
    }
  }

  private[cluster] def joinCluster() {
    nodeNameToAddress += (nodeAddress.nodeName -> remoteServerAddress)
    try {
      EventHandler.info(this,
        "Joining cluster as membership node [%s] on [%s]".format(nodeAddress, membershipNodePath))
      zkClient.createEphemeral(membershipNodePath, remoteServerAddress)
    } catch {
      case e: ZkNodeExistsException ⇒
        val error = new ClusterException("Can't join the cluster. The node name [" + nodeAddress.nodeName + "] is already in by another node")
        EventHandler.error(error, this, error.toString)
        throw error
    }
  }

  private[cluster] def joinLeaderElection(): Boolean = {
    EventHandler.info(this, "Node [%s] is joining leader election".format(nodeAddress.nodeName))
    try {
      leaderLock.lock
    } catch {
      case e: KeeperException.NodeExistsException ⇒ false
    }
  }

  private[cluster] def remoteSocketAddressForNode(node: String): Option[InetSocketAddress] = {
    try {
      Some(zkClient.readData(membershipPathFor(node), new Stat).asInstanceOf[InetSocketAddress])
    } catch {
      case e: ZkNoNodeException ⇒ None
    }
  }

  private[cluster] def createActorsAtAddressPath() {
    ignore[ZkNodeExistsException](zkClient.createPersistent(actorsAtNodePathFor(nodeAddress.nodeName)))
  }

  private[cluster] def failOverConnections(from: InetSocketAddress, to: InetSocketAddress) {
    clusterActorRefs.values(from) foreach (_.failOver(from, to))
  }

  private[cluster] def migrateActorsOnFailedNodes(currentSetOfClusterNodes: List[String]) {
    connectToAllNewlyArrivedMembershipNodesInCluster(currentSetOfClusterNodes)

    val failedNodes = findFailedNodes(currentSetOfClusterNodes)

    failedNodes.foreach { failedNodeName ⇒

      val allNodes = locallyCachedMembershipNodes.toList
      val myIndex = allNodes.indexWhere(_.endsWith(nodeAddress.nodeName))
      val failedNodeIndex = allNodes.indexWhere(_ == failedNodeName)

      // Migrate to the successor of the failed node (using a sorted circular list of the node names)
      if ((failedNodeIndex == 0 && myIndex == locallyCachedMembershipNodes.size - 1) || // No leftmost successor exists, check the tail
        (failedNodeIndex == myIndex + 1)) { // Am I the leftmost successor?

        // Yes I am the node to migrate the actor to (can only be one in the cluster)
        val actorUuidsForFailedNode = zkClient.getChildren(actorsAtNodePathFor(failedNodeName))

        EventHandler.debug(this,
          "Migrating actors from failed node [%s] to node [%s]: Actor UUIDs [%s]"
            .format(failedNodeName, nodeAddress.nodeName, actorUuidsForFailedNode))

        actorUuidsForFailedNode.foreach { uuid ⇒
          EventHandler.debug(this,
            "Cluster node [%s] has failed, migrating actor with UUID [%s] to [%s]"
              .format(failedNodeName, uuid, nodeAddress.nodeName))

          val actorAddressOption = actorAddressForUuid(uuidFrom(uuid))
          if (actorAddressOption.isDefined) {
            val actorAddress = actorAddressOption.get

            migrateWithoutCheckingThatActorResidesOnItsHomeNode( // since the ephemeral node is already gone, so can't check
              NodeAddress(nodeAddress.clusterName, failedNodeName), nodeAddress, actorAddress)

            use(actorAddress, serializerForActor(actorAddress)) foreach (actor ⇒ remoteService.register(actorAddress, actor))
          }
        }

        // notify all available nodes that they should fail-over all connections from 'from' to 'to'
        val from = nodeNameToAddress(failedNodeName)
        val to = remoteServerAddress

        Serialization.serialize((from, to)) match {
          case Left(error) ⇒ throw error
          case Right(bytes) ⇒

            val command = RemoteDaemonMessageProtocol.newBuilder
              .setMessageType(FAIL_OVER_CONNECTIONS)
              .setPayload(ByteString.copyFrom(bytes))
              .build

            // FIXME now we are broadcasting to ALL nodes in the cluster even though a fraction might have a reference to the actors - should that be fixed?
            currentSetOfClusterNodes foreach { node ⇒
              nodeConnections.get(node) foreach {
                case (_, connection) ⇒ sendCommandToReplica(connection, command, async = true)
              }
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

    EventHandler.debug(this, "Migrating actor [%s] from node [%s] to node [%s]".format(actorAddress, from, to))

    actorUuidsForActorAddress(actorAddress) map { uuid ⇒
      val actorAddressOption = actorAddressForUuid(uuid)
      if (actorAddressOption.isDefined) {
        val actorAddress = actorAddressOption.get

        if (!isInUseOnNode(actorAddress, to)) {
          release(actorAddress)

          ignore[ZkNodeExistsException](zkClient.createPersistent(actorRegistryNodePathFor(uuid)))
          ignore[ZkNodeExistsException](zkClient.createEphemeral(actorRegistryNodePathFor(uuid,
            remoteSocketAddressForNode(to.nodeName).getOrElse(throw new ClusterException("No remote address registered for [" + to.nodeName + "]")))))

          ignore[ZkNodeExistsException](zkClient.createEphemeral(actorLocationsPathFor(uuid, to)))
          ignore[ZkNodeExistsException](zkClient.createPersistent(actorAtNodePathFor(nodeAddress.nodeName, uuid)))

          ignore[ZkNoNodeException](zkClient.delete(actorLocationsPathFor(uuid, from)))
          ignore[ZkNoNodeException](zkClient.delete(actorAtNodePathFor(from.nodeName, uuid)))

          // 'use' (check out) actor on the remote 'to' node
          useActorOnNode(to.nodeName, uuid)
        }
      }
    }
  }

  private[cluster] def findFailedNodes(nodes: List[String]): List[String] =
    (locallyCachedMembershipNodes.toArray.toSet.asInstanceOf[Set[String]] diff Set(nodes: _*)).toList

  private[cluster] def findNewlyConnectedMembershipNodes(nodes: List[String]): List[String] =
    (Set(nodes: _*) diff locallyCachedMembershipNodes.toArray.toSet.asInstanceOf[Set[String]]).toList

  private[cluster] def findNewlyDisconnectedMembershipNodes(nodes: List[String]): List[String] =
    (locallyCachedMembershipNodes.toArray.toSet.asInstanceOf[Set[String]] diff Set(nodes: _*)).toList

  private[cluster] def findNewlyConnectedAvailableNodes(nodes: List[String]): List[String] =
    (Set(nodes: _*) diff locallyCachedMembershipNodes.toArray.toSet.asInstanceOf[Set[String]]).toList

  private[cluster] def findNewlyDisconnectedAvailableNodes(nodes: List[String]): List[String] =
    (locallyCachedMembershipNodes.toArray.toSet.asInstanceOf[Set[String]] diff Set(nodes: _*)).toList

  private def createRootClusterNode() {
    ignore[ZkNodeExistsException] {
      zkClient.create(CLUSTER_PATH, null, CreateMode.PERSISTENT)
      EventHandler.info(this, "Created node [%s]".format(CLUSTER_PATH))
    }
  }

  private def createNodeStructureIfNeeded() {
    basePaths.foreach { path ⇒
      try {
        ignore[ZkNodeExistsException](zkClient.create(path, null, CreateMode.PERSISTENT))
        EventHandler.debug(this, "Created node [%s]".format(path))
      } catch {
        case e ⇒
          val error = new ClusterException(e.toString)
          EventHandler.error(error, this)
          throw error
      }
    }
  }

  private def registerListeners() = {
    zkClient.subscribeStateChanges(stateListener)
    zkClient.subscribeChildChanges(MEMBERSHIP_PATH, membershipListener)
  }

  private def unregisterListeners() = {
    zkClient.unsubscribeStateChanges(stateListener)
    zkClient.unsubscribeChildChanges(MEMBERSHIP_PATH, membershipListener)
  }

  private def fetchMembershipNodes() {
    val membershipChildren = zkClient.getChildren(MEMBERSHIP_PATH)
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

      override def getRemoteServerHostname = self.hostname

      override def getRemoteServerPort = self.port

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

      override def getConfigElement(key: String) = new String(self.getConfigElement(key).getOrElse(Array[Byte]()), "UTF-8")

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

        self.migrateActorsOnFailedNodes(currentChilds.toList)

        self.findNewlyConnectedMembershipNodes(childList) foreach { name ⇒
          self.remoteSocketAddressForNode(name) foreach (address ⇒ self.nodeNameToAddress += (name -> address)) // update 'nodename-address' map
          self.publish(NodeConnected(name))
        }

        self.findNewlyDisconnectedMembershipNodes(childList) foreach { name ⇒
          self.nodeNameToAddress - name // update 'nodename-address' map
          self.publish(NodeDisconnected(name))
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
        self.publish(ThisNode.Connected)
      case KeeperState.Disconnected ⇒
        EventHandler.debug(this, "Cluster node [%s] - Disconnected".format(self.nodeAddress))
        self.publish(ThisNode.Disconnected)
      case KeeperState.Expired ⇒
        EventHandler.debug(this, "Cluster node [%s] - Expired".format(self.nodeAddress))
        self.publish(ThisNode.Expired)
    }
  }

  /**
   * Re-initialize after the zookeeper session has expired and a new session has been created.
   */
  def handleNewSession() {
    EventHandler.debug(this, "Session expired re-initializing node [%s]".format(self.nodeAddress))
    self.initializeNode()
    self.publish(NewSession)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ErrorHandler {
  def withErrorHandler[T](body: ⇒ T) = {
    try {
      ignore[ZkInterruptedException](body)
    } catch {
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

  // FIXME configure computeGridDispatcher to what?
  val computeGridDispatcher = Dispatchers.newDispatcher("akka:cloud:cluster:compute-grid").build
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClusterDaemon(cluster: ClusterNode) extends Actor {

  import RemoteClusterDaemon._
  import Cluster._

  self.dispatcher = Dispatchers.newPinnedDispatcher(self)

  override def preRestart(reason: Throwable) {
    EventHandler.debug(this, "RemoteClusterDaemon failed due to [%s] restarting...".format(reason))
  }

  def receive: Receive = {
    case message: RemoteDaemonMessageProtocol ⇒
      EventHandler.debug(this, "Received command to RemoteClusterDaemon [%s]".format(message))

      message.getMessageType match {

        case USE ⇒
          try {
            if (message.hasActorUuid) {
              for {
                address ← cluster.actorAddressForUuid(uuidProtocolToUuid(message.getActorUuid))
                serializer ← cluster.serializerForActor(address)
              } cluster.use(address, serializer)

            } else if (message.hasActorAddress) {
              val address = message.getActorAddress
              cluster.serializerForActor(address) foreach (serializer ⇒ cluster.use(address, serializer))

            } else {
              EventHandler.warning(this,
                "None of 'uuid', or 'address' is specified, ignoring remote cluster daemon command [%s]"
                  .format(message))
            }
            self.reply(Success)

          } catch {
            case error ⇒
              self.reply(Failure(error))
              throw error
          }

        case RELEASE ⇒
          if (message.hasActorUuid) {
            cluster.actorAddressForUuid(uuidProtocolToUuid(message.getActorUuid)) foreach { address ⇒
              cluster.release(address)
            }
          } else if (message.hasActorAddress) {
            cluster release message.getActorAddress
          } else {
            EventHandler.warning(this,
              "None of 'uuid' or 'actorAddress'' is specified, ignoring remote cluster daemon command [%s]"
                .format(message))
          }

        case START      ⇒ cluster.start()

        case STOP       ⇒ cluster.shutdown()

        case DISCONNECT ⇒ cluster.disconnect()

        case RECONNECT  ⇒ cluster.reconnect()

        case RESIGN     ⇒ cluster.resign()

        case FAIL_OVER_CONNECTIONS ⇒
          val (from, to) = payloadFor(message, classOf[(InetSocketAddress, InetSocketAddress)])
          cluster.failOverConnections(from, to)

        case FUNCTION_FUN0_UNIT ⇒
          localActorOf(new Actor() {
            self.dispatcher = computeGridDispatcher

            def receive = {
              case f: Function0[Unit] ⇒ try {
                f()
              } finally {
                self.stop()
              }
            }
          }).start ! payloadFor(message, classOf[Function0[Unit]])

        case FUNCTION_FUN0_ANY ⇒
          localActorOf(new Actor() {
            self.dispatcher = computeGridDispatcher

            def receive = {
              case f: Function0[Any] ⇒ try {
                self.reply(f())
              } finally {
                self.stop()
              }
            }
          }).start forward payloadFor(message, classOf[Function0[Any]])

        case FUNCTION_FUN1_ARG_UNIT ⇒
          localActorOf(new Actor() {
            self.dispatcher = computeGridDispatcher

            def receive = {
              case (fun: Function[Any, Unit], param: Any) ⇒ try {
                fun(param)
              } finally {
                self.stop()
              }
            }
          }).start ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])

        case FUNCTION_FUN1_ARG_ANY ⇒
          localActorOf(new Actor() {
            self.dispatcher = computeGridDispatcher

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
    Serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}
