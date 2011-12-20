/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.actor._
import akka.remote._
import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.config.ConfigFactory
import akka.config.ConfigurationException

trait RemoteRouterConfig extends RouterConfig {
  override protected def createRoutees(props: Props, context: ActorContext, nrOfInstances: Int, routees: Iterable[String]): Vector[ActorRef] = (nrOfInstances, routees) match {
    case (_, Nil) ⇒ throw new ConfigurationException("must specify list of remote nodes")
    case (n, xs) ⇒
      val nodes = routees map {
        case RemoteAddressExtractor(a) ⇒ a
        case x                         ⇒ throw new ConfigurationException("unparseable remote node " + x)
      }
      val node = Stream.continually(nodes).flatten.iterator
      val impl = context.system.asInstanceOf[ActorSystemImpl] //FIXME should we rely on this cast to work here?
      Vector.empty[ActorRef] ++ (for (i ← 1 to nrOfInstances) yield {
        val name = "c" + i
        val deploy = Deploy("", ConfigFactory.empty(), None, props.routerConfig, RemoteScope(node.next))
        impl.provider.actorOf(impl, props, context.self.asInstanceOf[InternalActorRef], context.self.path / name, false, Some(deploy))
      })
  }
}

/**
 * A Router that uses round-robin to select a connection. For concurrent calls, round robin is just a best effort.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the round robin should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RemoteRoundRobinRouter(nrOfInstances: Int, routees: Iterable[String]) extends RemoteRouterConfig with RoundRobinLike {

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(n: Int, t: java.util.Collection[String]) = this(n, t.asScala)
}

/**
 * A Router that randomly selects one of the target connections to send a message to.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RemoteRandomRouter(nrOfInstances: Int, routees: Iterable[String]) extends RemoteRouterConfig with RandomLike {

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(n: Int, t: java.util.Collection[String]) = this(n, t.asScala)
}

/**
 * A Router that uses broadcasts a message to all its connections.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RemoteBroadcastRouter(nrOfInstances: Int, routees: Iterable[String]) extends RemoteRouterConfig with BroadcastLike {

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(n: Int, t: java.util.Collection[String]) = this(n, t.asScala)
}

/**
 * Simple router that broadcasts the message to all routees, and replies with the first response.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RemoteScatterGatherFirstCompletedRouter(nrOfInstances: Int, routees: Iterable[String])
  extends RemoteRouterConfig with ScatterGatherFirstCompletedLike {

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(n: Int, t: java.util.Collection[String]) = this(n, t.asScala)
}
