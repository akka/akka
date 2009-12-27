/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import org.jgroups.{JChannel, View, Address, Message, ExtendedMembershipListener, Receiver}

import se.scalablesolutions.akka.Config.config
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor, ActorRegistry}
import se.scalablesolutions.akka.remote.Cluster.{Node, RelayedMessage}

import scala.collection.immutable.{Map, HashMap}
import se.scalablesolutions.akka.serialization.Serializer

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
}

/**
 * Base class for cluster actor implementations.
 */
abstract class ClusterActor extends Actor with Cluster {
  val name = config.getString("akka.remote.cluster.name") getOrElse "default"
}

/**
 * A singleton representing the Cluster.
 * <p/>
 * Loads a specified ClusterActor and delegates to that instance.
 */
object Cluster extends Cluster {
  private[remote] sealed trait ClusterMessage
  private[remote] case class Node(endpoints: List[RemoteAddress]) extends ClusterMessage
  private[remote] case class RelayedMessage(actorClassFQN: String, msg: AnyRef) extends ClusterMessage

  private[remote] lazy val clusterActor: Option[ClusterActor] = {
    config.getString("akka.remote.cluster.actor") map (name => {
      val actor = Class.forName(name)
          .newInstance
          .asInstanceOf[ClusterActor]
      SupervisorFactory(
        SupervisorConfig(
          RestartStrategy(OneForOne, 5, 1000, List(classOf[Exception])),
          Supervise(actor, LifeCycle(Permanent)) :: Nil)
        ).newInstance.start
      actor
    })
  }

  private[remote] lazy val serializer: Serializer = {
    val className = config.getString("akka.remote.cluster.serializer", Serializer.Java.getClass.getName)
    Class.forName(className).newInstance.asInstanceOf[Serializer]
  }

  def name = clusterActor.map(_.name).getOrElse("No cluster")

  def lookup[T](pf: PartialFunction[RemoteAddress, T]): Option[T] = clusterActor.flatMap(_.lookup(pf))

  def registerLocalNode(hostname: String, port: Int): Unit = clusterActor.map(_.registerLocalNode(hostname, port))

  def deregisterLocalNode(hostname: String, port: Int): Unit = clusterActor.map(_.deregisterLocalNode(hostname, port))

  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit = clusterActor.map(_.relayMessage(to, msg))
}

/**
 * JGroups Internal Cluster messages.
 */
private[remote] object JGroupsClusterActor {
  sealed trait JGroupsClusterMessage
  case object PapersPlease extends JGroupsClusterMessage
  case class Papers(addresses: List[RemoteAddress]) extends JGroupsClusterMessage
  case object Block extends JGroupsClusterMessage
  case object Unblock extends JGroupsClusterMessage
  case class Zombie(address: Address) extends JGroupsClusterMessage
  case class RegisterLocalNode(server: RemoteAddress) extends JGroupsClusterMessage
  case class DeregisterLocalNode(server: RemoteAddress) extends JGroupsClusterMessage
}

/**
 * Clustering support via JGroups.
 */
class JGroupsClusterActor extends ClusterActor {
  import JGroupsClusterActor._
  import org.scala_tools.javautils.Implicits._

  @volatile private var local: Node = Node(Nil)
  @volatile private var channel: Option[JChannel] = None
  @volatile private var remotes: Map[Address, Node] = Map()

  override def init = {
    log debug "Initiating JGroups-based cluster actor"
    remotes = new HashMap[Address, Node]
    val me = this

    // Set up the JGroups local endpoint
    channel = Some(new JChannel {
      setReceiver(new Receiver with ExtendedMembershipListener {
        def getState: Array[Byte] = null

        def setState(state: Array[Byte]): Unit = ()

        def receive(msg: Message): Unit = me send msg

        def viewAccepted(view: View): Unit = me send view

        def suspect(a: Address): Unit = me send Zombie(a)

        def block: Unit = me send Block

        def unblock: Unit = me send Unblock
      })
    })
    channel.map(_.connect(name))
  }

  def lookup[T](handleRemoteAddress: PartialFunction[RemoteAddress, T]): Option[T] =
    remotes.values.toList.flatMap(_.endpoints).find(handleRemoteAddress isDefinedAt _).map(handleRemoteAddress)

  def registerLocalNode(hostname: String, port: Int): Unit =
    send(RegisterLocalNode(RemoteAddress(hostname, port)))

  def deregisterLocalNode(hostname: String, port: Int): Unit =
    send(DeregisterLocalNode(RemoteAddress(hostname, port)))

  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit =
    send(RelayedMessage(to.getName, msg))

  private def broadcast[T <: AnyRef](recipients: Iterable[Address], msg: T): Unit = {
    lazy val m = Cluster.serializer out msg
    for (c <- channel; r <- recipients) c.send(new Message(r, null, m))
  }

  private def broadcast[T <: AnyRef](msg: T): Unit =
    //Don't broadcast if we are not connected anywhere...
    if (!remotes.isEmpty) channel.map(_.send(new Message(null, null, Cluster.serializer out msg)))

  def receive = {
    case Zombie(x) => { //Ask the presumed zombie for papers and prematurely treat it as dead
      log debug ("Killing Zombie Node: %s", x)
      broadcast(x :: Nil, PapersPlease)
      remotes = remotes - x
    }

    case v: View => {
      // Not present in the cluster anymore = presumably zombies
      // Nodes we have no prior knowledge existed = unknowns
      val members = Set[Address]() ++ v.getMembers.asScala - channel.get.getAddress // Exclude ourselves
      val zombies = Set[Address]() ++ remotes.keySet -- members
      val unknown = members -- remotes.keySet

      log debug v.printDetails

      // Tell the zombies and unknowns to provide papers and prematurely treat the zombies as dead
      broadcast(zombies ++ unknown, PapersPlease)
      remotes = remotes -- zombies
    }

    case m: Message => {
      if (m.getSrc != channel.map(_.getAddress).getOrElse(m.getSrc)) // Handle non-own messages only, and only if we're connected
        (Cluster.serializer in (m.getRawBuffer, None)) match {

          case PapersPlease => {
            log debug ("Asked for papers by %s", m.getSrc)
            broadcast(m.getSrc :: Nil, Papers(local.endpoints))

            if (remotes.get(m.getSrc).isEmpty) // If we were asked for papers from someone we don't know, ask them!
              broadcast(m.getSrc :: Nil, PapersPlease)
          }

          case Papers(x) => remotes = remotes + (m.getSrc -> Node(x))

          case RelayedMessage(c, m) => ActorRegistry.actorsFor(c).map(_ send m)

          case unknown => log debug ("Unknown message: %s", unknown.toString)
        }
    }

    case rm @ RelayedMessage(_, _) => {
      log debug ("Relaying message: %s", rm)
      broadcast(rm)
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

    case Block => log debug "UNSUPPORTED: JGroupsClusterActor::block" //TODO HotSwap to a buffering body
    case Unblock => log debug "UNSUPPORTED: JGroupsClusterActor::unblock" //TODO HotSwap back and flush the buffer
  }

  override def shutdown = {
    log debug ("Shutting down %s", this.getClass.getName)
    channel.map(_.shutdown)
    remotes = Map()
    channel = None
  }
}