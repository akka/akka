/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.actor._

import akka.japi.Creator
import java.lang.reflect.InvocationTargetException
import akka.config.ConfigurationException
import java.util.concurrent.atomic.AtomicInteger
import akka.util.ReflectiveAccess
import akka.AkkaException
import scala.collection.JavaConversions._
import java.util.concurrent.TimeUnit

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to
 * send a message to on (or more) of these actors.
 */
private[akka] class RoutedActorRef(_system: ActorSystemImpl, _props: Props, _supervisor: InternalActorRef, _path: ActorPath)
  extends LocalActorRef(
    _system,
    _props.copy(creator = () ⇒ _props.routerConfig.createActor()),
    _supervisor,
    _path) {

  val route: Route = ({
    case (_, _: AutoReceivedMessage) ⇒ Nil
  }: Route) orElse _props.routerConfig.createRoute(_props.creator, actorContext) orElse {
    case _ ⇒ Nil
  }

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = {
    val s = if (sender eq null) underlying.system.deadLetters else sender

    val msg = message match {
      case Broadcast(m) ⇒ m
      case m            ⇒ m
    }

    route(s, message) match {
      case Nil  ⇒ super.!(message)(s)
      case refs ⇒ refs foreach (p ⇒ p.recipient.!(msg)(p.sender))
    }
  }
}

/**
 * This trait represents a router factory: it produces the actual router actor
 * and creates the routing table (a function which determines the recipients
 * for each message which is to be dispatched). The resulting RoutedActorRef
 * optimizes the sending of the message so that it does NOT go through the
 * router’s mailbox unless the route returns an empty recipient set.
 *
 * '''Caution:''' This means
 * that the route function is evaluated concurrently without protection by
 * the RoutedActorRef: either provide a reentrant (i.e. pure) implementation or
 * do the locking yourself!
 *
 * '''Caution:''' Please note that the [[akka.routing.Router]] which needs to
 * be returned by `apply()` should not send a message to itself in its
 * constructor or `preStart()` or publish its self reference from there: if
 * someone tries sending a message to that reference before the constructor of
 * RoutedActorRef has returned, there will be a `NullPointerException`!
 */
trait RouterConfig {

  def nrOfInstances: Int

  def targets: Iterable[String]

  def createRoute(creator: () ⇒ Actor, actorContext: ActorContext): Route

  def createActor(): Router = new Router {}

  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig = {
    deploy match {
      case Some(Deploy(_, _, _, NoRouter, _)) ⇒ this
      case Some(Deploy(_, _, _, r, _))        ⇒ r
      case _                                  ⇒ this
    }
  }

  protected def toAll(sender: ActorRef, targets: Iterable[ActorRef]): Iterable[Destination] = targets.map(Destination(sender, _))

  protected def createRoutees(props: Props, context: ActorContext, nrOfInstances: Int, targets: Iterable[String]): Vector[ActorRef] = (nrOfInstances, targets) match {
    case (0, Nil) ⇒ throw new IllegalArgumentException("Insufficient information - missing configuration.")
    case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(props))(scala.collection.breakOut)
    case (_, xs)  ⇒ xs.map(context.actorFor(_))(scala.collection.breakOut)
  }
}

/**
 * Base trait for `Router` actors. Override `receive` to handle custom
 * messages which the corresponding [[akka.actor.RouterConfig]] lets
 * through by returning an empty route.
 */
trait Router extends Actor {
  final def receive = {
    case Terminated(child) ⇒
  }
}

/**
 * Used to broadcast a message to all connections in a router; only the
 * contained message will be forwarded, i.e. the `Broadcast(...)`
 * envelope will be stripped off.
 *
 * Router implementations may choose to handle this message differently.
 */
case class Broadcast(message: Any)

/**
 * Routing configuration that indicates no routing.
 * Oxymoron style.
 */
case object NoRouter extends RouterConfig {
  def nrOfInstances: Int = 0
  def targets: Iterable[String] = Nil
  def createRoute(creator: () ⇒ Actor, actorContext: ActorContext): Route = null
}

object RoundRobinRouter {
  def apply(targets: Iterable[ActorRef]) = new RoundRobinRouter(targets = targets map (_.path.toString))
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
case class RoundRobinRouter(nrOfInstances: Int = 0, targets: Iterable[String] = Nil) extends RouterConfig with RoundRobinLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the targets to be used.
   * Java API
   */
  def this(t: java.util.Collection[String]) = {
    this(targets = collectionAsScalaIterable(t))
  }
}

trait RoundRobinLike { this: RouterConfig ⇒
  def createRoute(creator: () ⇒ Actor, context: ActorContext): Route = {
    val routees: Vector[ActorRef] =
      createRoutees(context.props.copy(creator = creator, routerConfig = NoRouter), context, nrOfInstances, targets)

    val next = new AtomicInteger(0)

    def getNext(): ActorRef = {
      routees(next.getAndIncrement % routees.size)
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

object RandomRouter {
  def apply(targets: Iterable[ActorRef]) = new RandomRouter(targets = targets map (_.path.toString))
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
case class RandomRouter(nrOfInstances: Int = 0, targets: Iterable[String] = Nil) extends RouterConfig with RandomLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the targets to be used.
   * Java API
   */
  def this(t: java.util.Collection[String]) = {
    this(targets = collectionAsScalaIterable(t))
  }
}

trait RandomLike { this: RouterConfig ⇒

  import java.security.SecureRandom

  private val random = new ThreadLocal[SecureRandom] {
    override def initialValue = SecureRandom.getInstance("SHA1PRNG")
  }

  def createRoute(creator: () ⇒ Actor, context: ActorContext): Route = {
    val routees: Vector[ActorRef] =
      createRoutees(context.props.copy(creator = creator, routerConfig = NoRouter), context, nrOfInstances, targets)

    def getNext(): ActorRef = {
      routees(random.get.nextInt(routees.size))
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

object BroadcastRouter {
  def apply(targets: Iterable[ActorRef]) = new BroadcastRouter(targets = targets map (_.path.toString))
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
case class BroadcastRouter(nrOfInstances: Int = 0, targets: Iterable[String] = Nil) extends RouterConfig with BroadcastLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the targets to be used.
   * Java API
   */
  def this(t: java.util.Collection[String]) = {
    this(targets = collectionAsScalaIterable(t))
  }
}

trait BroadcastLike { this: RouterConfig ⇒
  def createRoute(creator: () ⇒ Actor, context: ActorContext): Route = {
    val routees: Vector[ActorRef] =
      createRoutees(context.props.copy(creator = creator, routerConfig = NoRouter), context, nrOfInstances, targets)

    {
      case (sender, message) ⇒
        message match {
          case _ ⇒ toAll(sender, routees)
        }
    }
  }
}

object ScatterGatherFirstCompletedRouter {
  def apply(targets: Iterable[ActorRef]) = new ScatterGatherFirstCompletedRouter(targets = targets map (_.path.toString))
}
/**
 * Simple router that broadcasts the message to all routees, and replies with the first response.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'targets' does not make logical sense as this means
 * that the random router should both create new actors and use the 'targets' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'targets' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'targets' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class ScatterGatherFirstCompletedRouter(nrOfInstances: Int = 0, targets: Iterable[String] = Nil)
  extends RouterConfig with ScatterGatherFirstCompletedLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the targets to be used.
   * Java API
   */
  def this(t: java.util.Collection[String]) = {
    this(targets = collectionAsScalaIterable(t))
  }
}

trait ScatterGatherFirstCompletedLike { this: RouterConfig ⇒
  def createRoute(creator: () ⇒ Actor, context: ActorContext): Route = {
    val routees: Vector[ActorRef] =
      createRoutees(context.props.copy(creator = creator, routerConfig = NoRouter), context, nrOfInstances, targets)

    {
      case (sender, message) ⇒
        val asker = context.asInstanceOf[ActorCell].systemImpl.provider.ask(Timeout(5, TimeUnit.SECONDS)).get // FIXME, NO REALLY FIXME!
        asker.result.pipeTo(sender)
        message match {
          case _ ⇒ toAll(asker, routees)
        }
    }
  }
}