/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.Config.config
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.actor.{Supervisor, SupervisorFactory, Actor, ActorRegistry}
import se.scalablesolutions.akka.util.Logging
import scala.collection.immutable.{Map, HashMap}

/**
 * Interface for interacting with the Cluster Membership API.
 *
 * @author Viktor Klang
 */
trait Cluster {
  def name: String

  def registerLocalNode(hostname: String, port: Int): Unit

  def deregisterLocalNode(hostname: String, port: Int): Unit

  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit

  def lookup[T](pf: PartialFunction[RemoteAddress, T]): Option[T]

  def foreach(f: (RemoteAddress) => Unit): Unit
}

/**
 * Base trait for Cluster implementations
 *
 * @author Viktor Klang
 */
trait ClusterActor extends Actor with Cluster {
  val name = config.getString("akka.remote.cluster.name") getOrElse "default"
  
  @volatile protected var serializer : Serializer = _
  
  private[remote] def setSerializer(s : Serializer) : Unit = serializer = s
}

/**
 * Companion object to ClusterActor that defines some common messages
 *
 * @author Viktor Klang
 */
private[remote] object ClusterActor {
  sealed trait ClusterMessage

  private[remote] case class RelayedMessage(actorClassFQN: String, msg: AnyRef) extends ClusterMessage
  private[remote] case class Message[ADDR_T](sender: ADDR_T, msg: Array[Byte])
  private[remote] case object PapersPlease extends ClusterMessage
  private[remote] case class Papers(addresses: List[RemoteAddress]) extends ClusterMessage
  private[remote] case object Block extends ClusterMessage
  private[remote] case object Unblock extends ClusterMessage
  private[remote] case class View[ADDR_T](othersPresent: Set[ADDR_T]) extends ClusterMessage
  private[remote] case class Zombie[ADDR_T](address: ADDR_T) extends ClusterMessage
  private[remote] case class RegisterLocalNode(server: RemoteAddress) extends ClusterMessage
  private[remote] case class DeregisterLocalNode(server: RemoteAddress) extends ClusterMessage
  private[remote] case class Node(endpoints: List[RemoteAddress])
}

/**
 * Base class for cluster actor implementations.
 * Provides most of the behavior out of the box
 * only needs to be gives hooks into the underlaying cluster impl.
 */
abstract class BasicClusterActor extends ClusterActor {
  import ClusterActor._
  type ADDR_T

  @volatile private var local: Node = Node(Nil)
  @volatile private var remotes: Map[ADDR_T, Node] = Map()

  override def init = {
    remotes = new HashMap[ADDR_T, Node]
  }

  override def shutdown = {
    remotes = Map()
  }

  def receive = {
    case v: View[ADDR_T] => {
      // Not present in the cluster anymore = presumably zombies
      // Nodes we have no prior knowledge existed = unknowns
      val zombies = Set[ADDR_T]() ++ remotes.keySet -- v.othersPresent
      val unknown = v.othersPresent -- remotes.keySet

      log debug ("Updating view")
      log debug ("Other memebers: [%s]", v.othersPresent)
      log debug ("Zombies: [%s]", zombies)
      log debug ("Unknowns: [%s]", unknown)

      // Tell the zombies and unknowns to provide papers and prematurely treat the zombies as dead
      broadcast(zombies ++ unknown, PapersPlease)
      remotes = remotes -- zombies
    }

    case z: Zombie[ADDR_T] => { //Ask the presumed zombie for papers and prematurely treat it as dead
      log debug ("Killing Zombie Node: %s", z.address)
      broadcast(z.address :: Nil, PapersPlease)
      remotes = remotes - z.address
    }

    case rm@RelayedMessage(_, _) => {
      log debug ("Relaying message: %s", rm)
      broadcast(rm)
    }

    case m: Message[ADDR_T] => {
      val (src, msg) = (m.sender, m.msg)
      (serializer in (msg, None)) match {

        case PapersPlease => {
          log debug ("Asked for papers by %s", src)
          broadcast(src :: Nil, Papers(local.endpoints))

          if (remotes.get(src).isEmpty) // If we were asked for papers from someone we don't know, ask them!
            broadcast(src :: Nil, PapersPlease)
        }

        case Papers(x) => remotes = remotes + (src -> Node(x))

        case RelayedMessage(c, m) => ActorRegistry.actorsFor(c).foreach(_ send m)

        case unknown => log debug ("Unknown message: %s", unknown.toString)
      }
    }

    case RegisterLocalNode(s) => {
      log debug ("RegisterLocalNode: %s", s)
      local = Node(local.endpoints + s)
      broadcast(Papers(local.endpoints))
    }

    case DeregisterLocalNode(s) => {
      log debug ("DeregisterLocalNode: %s", s)
      local = Node(local.endpoints - s)
      broadcast(Papers(local.endpoints))
    }
  }

  /**
   * Implement this in a subclass to add node-to-node messaging
   */
  protected def toOneNode(dest: ADDR_T, msg: Array[Byte]): Unit

  /**
   *  Implement this in a subclass to add node-to-many-nodes messaging
   */
  protected def toAllNodes(msg: Array[Byte]): Unit

  /**
   * Sends the specified message to the given recipients using the serializer
   * that's been set in the akka-conf
   */
  protected def broadcast[T <: AnyRef](recipients: Iterable[ADDR_T], msg: T): Unit = {
    lazy val m = serializer out msg
    for (r <- recipients) toOneNode(r, m)
  }

  /**
   * Sends the specified message toall other nodes using the serializer
   * that's been set in the akka-conf
   */
  protected def broadcast[T <: AnyRef](msg: T): Unit =
    if (!remotes.isEmpty) toAllNodes(serializer out msg)

  /**
   * Applies the given PartialFunction to all known RemoteAddresses
   */
  def lookup[T](handleRemoteAddress: PartialFunction[RemoteAddress, T]): Option[T] =
    remotes.values.toList.flatMap(_.endpoints).find(handleRemoteAddress isDefinedAt _).map(handleRemoteAddress)

  /**
   * Applies the given function to all remote addresses known
   */
  def foreach(f: (RemoteAddress) => Unit): Unit = remotes.values.toList.flatMap(_.endpoints).foreach(f)

  /**
   * Registers a local endpoint
   */
  def registerLocalNode(hostname: String, port: Int): Unit =
    send(RegisterLocalNode(RemoteAddress(hostname, port)))

  /**
   * Deregisters a local endpoint
   */
  def deregisterLocalNode(hostname: String, port: Int): Unit =
    send(DeregisterLocalNode(RemoteAddress(hostname, port)))

  /**
   * Broadcasts the specified message to all Actors of type Class on all known Nodes
   */
  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit =
    send(RelayedMessage(to.getName, msg))
}

/**
 *  A singleton representing the Cluster.
 * <p/>
 * Loads a specified ClusterActor and delegates to that instance.
 */
object Cluster extends Cluster with Logging {
  lazy val DEFAULT_SERIALIZER_CLASS_NAME = Serializer.Java.getClass.getName

  @volatile private[remote] var clusterActor: Option[ClusterActor] = None 

  private[remote] def createClusterActor(loader : ClassLoader): Option[ClusterActor] = {
    val name = config.getString("akka.remote.cluster.actor")
    if (name.isEmpty) throw new IllegalArgumentException(
      "Can't start cluster since the 'akka.remote.cluster.actor' configuration option is not defined")
      
    val serializer = Class.forName(config.getString("akka.remote.cluster.serializer", DEFAULT_SERIALIZER_CLASS_NAME)).newInstance.asInstanceOf[Serializer]
    serializer setClassLoader loader 
    try {
      name map {
        fqn =>
          val a = Class.forName(fqn).newInstance.asInstanceOf[ClusterActor]
          a setSerializer serializer
          a
      }
    }
    catch {
      case e => log.error(e, "Couldn't load Cluster provider: [%s]", name.getOrElse("Not specified")); None
    }
  }

  private[remote] def createSupervisor(actor: ClusterActor): Option[Supervisor] = {
    val sup = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(OneForOne, 5, 1000, List(classOf[Exception])),
        Supervise(actor, LifeCycle(Permanent)) :: Nil)
      ).newInstance
    Some(sup)
  }


  def name = clusterActor.map(_.name).getOrElse("No cluster")

  def lookup[T](pf: PartialFunction[RemoteAddress, T]): Option[T] = clusterActor.flatMap(_.lookup(pf))

  def registerLocalNode(hostname: String, port: Int): Unit = clusterActor.foreach(_.registerLocalNode(hostname, port))

  def deregisterLocalNode(hostname: String, port: Int): Unit = clusterActor.foreach(_.deregisterLocalNode(hostname, port))

  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit = clusterActor.foreach(_.relayMessage(to, msg))

  def foreach(f: (RemoteAddress) => Unit): Unit = clusterActor.foreach(_.foreach(f))

  def start: Unit = start(None)

  def start(serializerClassLoader : Option[ClassLoader]): Unit = synchronized {
    log.info("Starting up Cluster Service...")
    if (clusterActor.isEmpty) {
      for{ actor <- createClusterActor(serializerClassLoader getOrElse getClass.getClassLoader)
             sup <- createSupervisor(actor) } {
        clusterActor = Some(actor)
        sup.start
      }
    }
  }

  def shutdown: Unit = synchronized {
    log.info("Shutting down Cluster Service...")
    for{
      c <- clusterActor
      s <- c._supervisor
    } s.stop
    clusterActor = None
  }
}
