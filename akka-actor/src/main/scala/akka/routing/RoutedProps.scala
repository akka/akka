/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.util.Duration
import akka.actor._
import akka.util.ReflectiveAccess

import java.net.InetSocketAddress

import scala.collection.JavaConversions.iterableAsScalaIterable

sealed trait RouterType

/**
 * Used for declarative configuration of Routing.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RouterType {

  object Direct extends RouterType

  /**
   * A RouterType that randomly selects a connection to send a message to.
   */
  object Random extends RouterType

  /**
   * A RouterType that selects the connection by using round robin.
   */
  object RoundRobin extends RouterType

  /**
   * A RouterType that selects the connection based on the least amount of cpu usage
   */
  object LeastCPU extends RouterType

  /**
   * A RouterType that select the connection based on the least amount of ram used.
   *
   * FIXME: this is extremely vague currently since there are so many ways to define least amount of ram.
   */
  object LeastRAM extends RouterType

  /**
   * A RouterType that select the connection where the actor has the least amount of messages in its mailbox.
   */
  object LeastMessages extends RouterType

  /**
   * A user-defined custom RouterType.
   */
  object Custom extends RouterType

}

object RoutedProps {

  final val defaultTimeout = Timeout(Duration.MinusInf)
  final val defaultRouterFactory = () ⇒ new RoundRobinRouter
  final val defaultLocalOnly = false
  final val defaultFailureDetectorFactory = (connections: Map[InetSocketAddress, ActorRef]) ⇒ new RemoveConnectionOnFirstFailureLocalFailureDetector(connections.values)

  /**
   * The default RoutedProps instance, uses the settings from the RoutedProps object starting with default*
   */
  final val default = new RoutedProps

  def apply(): RoutedProps = default
}

/**
 * Contains the configuration to create local and clustered routed actor references.
 *
 * Routed ActorRef configuration object, this is thread safe and fully sharable.
 *
 * Because the Routers are stateful, a new Router instance needs to be created for every ActorRef that relies on routing
 * (currently the ClusterActorRef and the RoutedActorRef). That is why a Router factory is used (a function that returns
 * a new Router instance) instead of a single Router instance. This makes sharing the same RoutedProps between multiple
 * threads safe.
 *
 * This configuration object makes it possible to either.
 */
case class RoutedProps(
  routerFactory: () ⇒ Router,
  connections: Iterable[ActorRef],
  failureDetectorFactory: (Map[InetSocketAddress, ActorRef]) ⇒ FailureDetector = RoutedProps.defaultFailureDetectorFactory,
  timeout: Timeout = RoutedProps.defaultTimeout,
  localOnly: Boolean = RoutedProps.defaultLocalOnly) {

  def this() = this(RoutedProps.defaultRouterFactory, List())

  /**
   * Returns a new RoutedProps configured with a random router.
   *
   * Java and Scala API.
   */
  def withRandomRouter: RoutedProps = copy(routerFactory = () ⇒ new RandomRouter)

  /**
   * Returns a new RoutedProps configured with a round robin router.
   *
   * Java and Scala API.
   */
  def withRoundRobinRouter: RoutedProps = copy(routerFactory = () ⇒ new RoundRobinRouter)

  /**
   * Returns a new RoutedProps configured with a direct router.
   *
   * Java and Scala API.
   */
  def withDirectRouter: RoutedProps = copy(routerFactory = () ⇒ new DirectRouter)

  /**
   * Makes it possible to change the default behavior in a clustered environment that a clustered actor ref is created.
   * In some cases you just want to have local actor references, even though the Cluster Module is up and running.
   *
   * Java and Scala API.
   */
  def withLocalOnly(l: Boolean = true) = copy(localOnly = l)

  /**
   * Sets the Router factory method to use. Since Router instance contain state, and should be linked to a single 'routed' ActorRef, a new
   * Router instance is needed for every 'routed' ActorRef. That is why a 'factory' function is used to create new
   * instances.
   *
   * Scala API.
   */
  def withRouter(f: () ⇒ Router): RoutedProps = copy(routerFactory = f)

  /**
   * Sets the RouterFactory to use. Since Router instance contain state, and should be linked to a single 'routed' ActorRef, a new
   * Router instance is needed for every 'routed' ActorRef. That is why a RouterFactory interface is used to create new
   * instances.
   *
   * Java API.
   */
  def withRouter(f: RouterFactory): RoutedProps = copy(routerFactory = () ⇒ f.newRouter())

  /**
   *
   */
  def withTimeout(t: Timeout): RoutedProps = copy(timeout = t)

  /**
   * Sets the connections to use.
   *
   * Scala API.
   */
  def withConnections(c: Iterable[ActorRef]): RoutedProps = copy(connections = c)

  /**
   * Sets the connections to use.
   *
   * Java API.
   */
  def withConnections(c: java.lang.Iterable[ActorRef]): RoutedProps = copy(connections = iterableAsScalaIterable(c))

  /**
   * Returns a new RoutedProps configured with a FailureDetector factory.
   *
   * Scala API.
   */
  def withFailureDetector(failureDetectorFactory: (Map[InetSocketAddress, ActorRef]) ⇒ FailureDetector): RoutedProps =
    copy(failureDetectorFactory = failureDetectorFactory)

  /**
   * Returns a new RoutedProps configured with a FailureDetector factory.
   *
   * Java API.
   */
  def withFailureDetector(failureDetectorFactory: akka.japi.Function[Map[InetSocketAddress, ActorRef], FailureDetector]): RoutedProps =
    copy(failureDetectorFactory = (connections: Map[InetSocketAddress, ActorRef]) ⇒ failureDetectorFactory.apply(connections))
}
