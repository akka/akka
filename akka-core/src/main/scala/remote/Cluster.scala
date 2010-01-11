/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.Config.config
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.remote.Cluster.{Node, RelayedMessage}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.actor.{Supervisor, SupervisorFactory, Actor, ActorRegistry}

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

  private[remote] val clusterActor: Option[ClusterActor] =
    config.getString("akka.remote.cluster.actor") map { name =>
      val a = Class.forName(name).newInstance.asInstanceOf[ClusterActor]
      a.start
      a
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
