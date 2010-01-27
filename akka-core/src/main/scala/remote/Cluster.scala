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
}

private[remote] object ClusterActor {
  sealed trait ClusterMessage

  private[remote] case class RelayedMessage(actorClassFQN: String, msg: AnyRef) extends ClusterMessage

  private[remote] case class Node(endpoints: List[RemoteAddress])
}

/**
 * Base class for cluster actor implementations.
 */
abstract class ClusterActor extends Actor with Cluster {
  import ClusterActor._

  case class Message(sender : ADDR_T,msg : Array[Byte])
  case object PapersPlease extends ClusterMessage
  case class Papers(addresses: List[RemoteAddress]) extends ClusterMessage
  case object Block extends ClusterMessage
  case object Unblock extends ClusterMessage
  case class View(othersPresent : Set[ADDR_T]) extends ClusterMessage
  case class Zombie(address: ADDR_T) extends ClusterMessage
  case class RegisterLocalNode(server: RemoteAddress) extends ClusterMessage
  case class DeregisterLocalNode(server: RemoteAddress) extends ClusterMessage

  type ADDR_T



  @volatile private var local: Node = Node(Nil)
  @volatile private var remotes: Map[ADDR_T, Node] = Map()

  val name = config.getString("akka.remote.cluster.name") getOrElse "default"

  override def init = {
      remotes = new HashMap[ADDR_T, Node]
  }

  override def shutdown = {
      remotes = Map()
  }

  def receive = {
    case v @ View(members) => {
      // Not present in the cluster anymore = presumably zombies
      // Nodes we have no prior knowledge existed = unknowns
      val zombies = Set[ADDR_T]() ++ remotes.keySet -- members
      val unknown = members -- remotes.keySet

      log debug ("Updating view")
      log debug ("Other memebers: [%s]",members)
      log debug ("Zombies: [%s]",zombies)
      log debug ("Unknowns: [%s]",unknown)

      // Tell the zombies and unknowns to provide papers and prematurely treat the zombies as dead
      broadcast(zombies ++ unknown, PapersPlease)
      remotes = remotes -- zombies
    }

    case Zombie(x) => { //Ask the presumed zombie for papers and prematurely treat it as dead
      log debug ("Killing Zombie Node: %s", x)
      broadcast(x :: Nil, PapersPlease)
      remotes = remotes - x
    }

    case rm @ RelayedMessage(_, _) => {
      log debug ("Relaying message: %s", rm)
      broadcast(rm)
    }

    case m @ Message(src,msg) => {
        (Cluster.serializer in (msg, None)) match {

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

  protected def toOneNode(dest : ADDR_T, msg : Array[Byte]) : Unit
  protected def toAllNodes(msg : Array[Byte]) : Unit

  protected def broadcast[T <: AnyRef](recipients: Iterable[ADDR_T], msg: T): Unit = {
    lazy val m = Cluster.serializer out msg
    for (r <- recipients) toOneNode(r,m)
  }

  protected def broadcast[T <: AnyRef](msg: T): Unit =
    if (!remotes.isEmpty) toAllNodes(Cluster.serializer out msg)  

  def lookup[T](handleRemoteAddress: PartialFunction[RemoteAddress, T]): Option[T] =
    remotes.values.toList.flatMap(_.endpoints).find(handleRemoteAddress isDefinedAt _).map(handleRemoteAddress)

  def registerLocalNode(hostname: String, port: Int): Unit =
    send(RegisterLocalNode(RemoteAddress(hostname, port)))

  def deregisterLocalNode(hostname: String, port: Int): Unit =
    send(DeregisterLocalNode(RemoteAddress(hostname, port)))

  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit =
    send(RelayedMessage(to.getName, msg))
}

/**
 * A singleton representing the Cluster.
 * <p/>
 * Loads a specified ClusterActor and delegates to that instance.
 */
object Cluster extends Cluster with Logging {
  private[remote] val clusterActor: Option[ClusterActor] = {
    val name = config.getString("akka.remote.cluster.actor","not defined")
    try { 
        val a = Class.forName(name).newInstance.asInstanceOf[ClusterActor]
        a.start
        Some(a)
      } 
      catch { 
        case e => log.error(e,"Couldn't load Cluster provider: [%s]",name)
                  None
      }
  }

  private[remote] val supervisor: Option[Supervisor] = if (clusterActor.isDefined) {
    val sup = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(OneForOne, 5, 1000, List(classOf[Exception])),
        Supervise(clusterActor.get, LifeCycle(Permanent)) :: Nil)
      ).newInstance
    sup.start
    Some(sup)
  } else None
  
  private[remote] lazy val serializer: Serializer = {
    val className = config.getString("akka.remote.cluster.serializer", Serializer.Java.getClass.getName)
    Class.forName(className).newInstance.asInstanceOf[Serializer]
  }

  def name = clusterActor.map(_.name).getOrElse("No cluster")

  def lookup[T](pf: PartialFunction[RemoteAddress, T]): Option[T] = clusterActor.flatMap(_.lookup(pf))

  def registerLocalNode(hostname: String, port: Int): Unit = clusterActor.foreach(_.registerLocalNode(hostname, port))

  def deregisterLocalNode(hostname: String, port: Int): Unit = clusterActor.foreach(_.deregisterLocalNode(hostname, port))

  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit = clusterActor.foreach(_.relayMessage(to, msg))

  def shutdown = supervisor.foreach(_.stop)
}
