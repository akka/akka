/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.actor._

import akka.japi.Creator
import java.lang.reflect.InvocationTargetException
import akka.config.ConfigurationException
import akka.routing.Routing.Broadcast
import akka.actor.DeploymentConfig.Deploy
import java.util.concurrent.atomic.AtomicInteger
import akka.dispatch.Future
import akka.util.{ Duration, ReflectiveAccess }
import java.util.concurrent.TimeUnit
import akka.AkkaException

sealed trait RouterType

/**
 * Used for declarative configuration of Routing.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RouterType {

  /**
   * A RouterType that indicates no routing - i.e. direct message.
   */
  object NoRouter extends RouterType

  /**
   * A RouterType that randomly selects a connection to send a message to.
   */
  object Random extends RouterType

  /**
   * A RouterType that selects the connection by using round robin.
   */
  object RoundRobin extends RouterType

  /**
   * A RouterType that selects the connection by using scatter gather.
   */
  object ScatterGather extends RouterType

  /**
   * A RouterType that broadcasts the messages to all connections.
   */
  object Broadcast extends RouterType

  /**
   * A RouterType that selects the connection based on the least amount of cpu usage
   */
  object LeastCPU extends RouterType

  /**
   * A RouterType that select the connection based on the least amount of ram used.
   */
  object LeastRAM extends RouterType

  /**
   * A RouterType that select the connection where the actor has the least amount of messages in its mailbox.
   */
  object LeastMessages extends RouterType

  /**
   * A user-defined custom RouterType.
   */
  case class Custom(implClass: String) extends RouterType

}

/**
 * An {@link AkkaException} thrown when something goes wrong while routing a message
 */
class RoutingException(message: String) extends AkkaException(message)

/**
 * Contains the configuration to create local and clustered routed actor references.
 * Routed ActorRef configuration object, this is thread safe and fully sharable.
 */
case class RoutedProps private[akka] (
  routerFactory: () ⇒ Router,
  connectionManager: ConnectionManager) {

  // Java API
  def this(creator: Creator[Router], connectionManager: ConnectionManager) {
    this(() ⇒ creator.create(), connectionManager)
  }
}

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to
 * send a message to on (or more) of these actors.
 */
private[akka] class RoutedActorRef(_system: ActorSystemImpl, _props: Props, _supervisor: InternalActorRef, _path: ActorPath)
  extends LocalActorRef(
    _system,
    _props.copy(creator = _props.routerConfig),
    _supervisor,
    _path) {

  val route: Routing.Route = _props.routerConfig.createRoute(_props.creator, actorContext)

  override def !(message: Any)(implicit sender: ActorRef = null) {
    route(message) match {
      case null          ⇒ super.!(message)(sender)
      case ref: ActorRef ⇒ ref.!(message)(sender)
      case refs: Traversable[ActorRef] ⇒
        message match {
          case Broadcast(m) ⇒ refs foreach (_.!(m)(sender))
          case _            ⇒ refs foreach (_.!(message)(sender))
        }
    }
  }

  // TODO (HE) : Should the RoutedActorRef also override "?"?
  // If not how then Broadcast messages cannot be sent via ? -
  // which it is in some test cases at the moment.
}

trait RouterConfig extends Function0[Actor] {
  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig

  def createRoute(creator: () ⇒ Actor, actorContext: ActorContext): Routing.Route
}

/**
 * A Router is responsible for sending a message to one (or more) of its connections.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Router {
}

/**
 * A Helper class to create actor references that use routing.
 */
object Routing {

  sealed trait RoutingMessage

  /**
   * Used to broadcast a message to all connections in a router. E.g. every connection gets the message
   * regardless of their routing algorithm.
   */
  case class Broadcast(message: Any) extends RoutingMessage

  def createCustomRouter(implClass: String): Router = {
    ReflectiveAccess.createInstance(implClass, Array[Class[_]](), Array[AnyRef]()) match {
      case Right(router) ⇒ router.asInstanceOf[Router]
      case Left(exception) ⇒
        val cause = exception match {
          case i: InvocationTargetException ⇒ i.getTargetException
          case _                            ⇒ exception
        }

        throw new ConfigurationException("Could not instantiate custom Router of [" +
          implClass + "] due to: " + cause, cause)
    }
  }

  type Route = (Any) ⇒ AnyRef
}

/**
 * Routing configuration that indicates no routing.
 * Oxymoron style.
 */
case object NoRouter extends RouterConfig {
  def adaptFromDeploy(deploy: Option[Deploy]) = null

  def createRoute(creator: () ⇒ Actor, actorContext: ActorContext) = null

  def apply(): Actor = null
}

/**
 * A Router that uses round-robin to select a connection. For concurrent calls, round robin is just a best effort.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'targets' does not make logical sense as this means
 * that the round robin should both create new actors and use the 'targets' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'targets' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'targets' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RoundRobinRouter(nrOfInstances: Int = 0, targets: Iterable[ActorRef] = Nil)
  extends Router with RouterConfig {

  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig = {
    deploy match {
      case Some(d) ⇒
        // In case there is a config then use this over any programmed settings.
        copy(nrOfInstances = d.nrOfInstances.factor, targets = Nil)
      case _ ⇒ this
    }
  }

  def apply(): Actor = new Actor {
    def receive = {
      case _ ⇒
    }
  }

  def createRoute(creator: () ⇒ Actor, context: ActorContext): Routing.Route = {
    val routees: Vector[ActorRef] = (nrOfInstances, targets) match {
      case (0, Nil) ⇒ throw new IllegalArgumentException("Insufficient information - missing configuration.")
      case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(context.props.copy(creator = creator, routerConfig = NoRouter)))(scala.collection.breakOut)
      case (x, xs)  ⇒ Vector.empty[ActorRef] ++ xs
    }

    val next = new AtomicInteger(0)

    def getNext(): ActorRef = {
      routees(next.getAndIncrement % routees.size)
    }

    {
      case msg: AutoReceivedMessage ⇒ null // TODO (HE): how should system messages be handled?
      case Broadcast(msg)           ⇒ routees
      case msg                      ⇒ getNext()
    }
  }
}

/**
 * A Router that randomly selects one of the target connections to send a message to.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'targets' does not make logical sense as this means
 * that the random router should both create new actors and use the 'targets' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'targets' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'targets' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RandomRouter(nrOfInstances: Int = 0, targets: Iterable[ActorRef] = Nil)
  extends Router with RouterConfig {

  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig = {
    deploy match {
      case Some(d) ⇒
        // In case there is a config then use this over any programmed settings.
        copy(nrOfInstances = d.nrOfInstances.factor, targets = Nil)
      case _ ⇒ this
    }
  }

  def apply(): Actor = new Actor {
    def receive = {
      case _ ⇒
    }
  }

  import java.security.SecureRandom

  private val random = new ThreadLocal[SecureRandom] {
    override def initialValue = SecureRandom.getInstance("SHA1PRNG")
  }

  def createRoute(creator: () ⇒ Actor, context: ActorContext): Routing.Route = {
    val routees: Vector[ActorRef] = (nrOfInstances, targets) match {
      case (0, Nil) ⇒ throw new IllegalArgumentException("Insufficient information - missing configuration.")
      case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(context.props.copy(creator = creator, routerConfig = NoRouter)))(scala.collection.breakOut)
      case (x, xs)  ⇒ Vector.empty[ActorRef] ++ xs
    }

    def getNext(): ActorRef = {
      routees(random.get.nextInt(routees.size))
    }

    {
      case msg: AutoReceivedMessage ⇒ null // TODO (HE): how should system messages be handled?
      case Broadcast(msg)           ⇒ routees
      case msg                      ⇒ getNext()
    }
  }
}

/**
 * A Router that uses broadcasts a message to all its connections.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'targets' does not make logical sense as this means
 * that the random router should both create new actors and use the 'targets' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'targets' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'targets' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class BroadcastRouter(nrOfInstances: Int = 0, targets: Iterable[ActorRef] = Nil)
  extends Router with RouterConfig {

  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig = {
    deploy match {
      case Some(d) ⇒
        // In case there is a config then use this over any programmed settings.
        copy(nrOfInstances = d.nrOfInstances.factor, targets = Nil)
      case _ ⇒ this
    }
  }

  def apply(): Actor = new Actor {
    def receive = {
      case _ ⇒
    }
  }

  def createRoute(creator: () ⇒ Actor, context: ActorContext): Routing.Route = {
    val routees: Vector[ActorRef] = (nrOfInstances, targets) match {
      case (0, Nil) ⇒ throw new IllegalArgumentException("Insufficient information - missing configuration.")
      case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(context.props.copy(creator = creator, routerConfig = NoRouter)))(scala.collection.breakOut)
      case (x, xs)  ⇒ Vector.empty[ActorRef] ++ xs
    }

    {
      case msg: AutoReceivedMessage ⇒ null // TODO (HE): how should system messages be handled?
      case Broadcast(msg)           ⇒ routees
      case msg                      ⇒ routees
    }
  }
}

// TODO (HE) : Correct description below
/**
 * Simple router that broadcasts the message to all connections, and replies with the first response.
 * Scatter-gather pattern will be applied only to the messages broadcast using Future
 * (wrapped into {@link Routing.Broadcast} and sent with "?" method).
 * For the messages sent in a fire-forget mode, the router would behave as {@link RoundRobinRouter}
 */
case class ScatterGatherFirstCompletedRouter(nrOfInstances: Int = 0, targets: Iterable[ActorRef] = Nil) extends Router with RouterConfig {

  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig = {
    deploy match {
      case Some(d) ⇒
        // In case there is a config then use this over any programmed settings.
        copy(nrOfInstances = d.nrOfInstances.factor, targets = Nil)
      case _ ⇒ this
    }
  }

  def apply(): Actor = new Actor {
    def receive = {
      case _ ⇒
    }
  }

  def createRoute(creator: () ⇒ Actor, context: ActorContext): Routing.Route = {
    val routees: Vector[ActorRef] = (nrOfInstances, targets) match {
      case (0, Nil) ⇒ throw new IllegalArgumentException("Insufficient information - missing configuration.")
      case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(context.props.copy(creator = creator, routerConfig = NoRouter)))(scala.collection.breakOut)
      case (x, xs)  ⇒ Vector.empty[ActorRef] ++ xs
    }

    def scatterGather[S, G >: S](message: Any, t: Timeout): Future[G] = {
      val responses = routees.flatMap { actor ⇒
        try {
          if (actor.isTerminated) None else Some(actor.?(message, t).asInstanceOf[Future[S]])
        } catch {
          case e: Exception ⇒ None
        }
      }

      if (!responses.isEmpty) throw new RoutingException("No connections can process the message [%s] sent to scatter-gather router" format (message))
      else {
        implicit val messageDispatcher = context.dispatcher
        implicit val timeout = t
        Future.firstCompletedOf(responses)
      }
    }

    // TODO (HE) : Timeout and Future should be updated to new strategy - or hardcoded value below should at least be removed!
    {
      case msg: AutoReceivedMessage ⇒ null // TODO (HE): how should system messages be handled?
      case Broadcast(msg)           ⇒ routees
      case msg                      ⇒ scatterGather(msg, Timeout(Duration(5000, TimeUnit.MILLISECONDS)))
    }
  }
}
