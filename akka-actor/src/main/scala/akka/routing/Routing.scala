/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.actor._
import akka.util.Duration
import akka.util.duration._
import akka.config.ConfigurationException
import akka.pattern.pipe
import akka.pattern.AskSupport

import com.typesafe.config.Config

import scala.collection.JavaConversions.iterableAsScalaIterable

import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean }
import java.util.concurrent.TimeUnit

import akka.jsr166y.ThreadLocalRandom

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

  private val routeeProps = _props.copy(routerConfig = NoRouter)
  private val resizeProgress = new AtomicBoolean
  private val resizeCounter = new AtomicLong

  @volatile
  private var _routees: IndexedSeq[ActorRef] = IndexedSeq.empty[ActorRef] // this MUST be initialized during createRoute
  def routees = _routees

  /**
   * Adds the routees to existing routees.
   * Adds death watch of the routees so that they are removed when terminated.
   * Not thread safe, but intended to be called from protected points, such as
   * `RouterConfig.createRoute` and `Resizer.resize`
   */
  private[akka] def addRoutees(newRoutees: IndexedSeq[ActorRef]) {
    _routees = _routees ++ newRoutees
    // subscribe to Terminated messages for all route destinations, to be handled by Router actor
    newRoutees foreach underlying.watch
  }

  /**
   * Adds the routees to existing routees.
   * Removes death watch of the routees. Doesn't stop the routees.
   * Not thread safe, but intended to be called from protected points, such as
   * `Resizer.resize`
   */
  private[akka] def removeRoutees(abandonedRoutees: IndexedSeq[ActorRef]) {
    _routees = _routees diff abandonedRoutees
    abandonedRoutees foreach underlying.unwatch
  }

  private val routeeProvider = _props.routerConfig.createRouteeProvider(actorContext)
  val route = _props.routerConfig.createRoute(routeeProps, routeeProvider)
  // initial resize, before message send
  resize()

  def applyRoute(sender: ActorRef, message: Any): Iterable[Destination] = message match {
    case _: AutoReceivedMessage ⇒ Nil
    case Terminated(_)          ⇒ Nil
    case CurrentRoutees ⇒
      sender ! RouterRoutees(_routees)
      Nil
    case _ ⇒
      if (route.isDefinedAt(sender, message)) route(sender, message)
      else Nil
  }

  if (_props.routerConfig.resizer.isEmpty && _routees.isEmpty)
    throw new ActorInitializationException("router " + _props.routerConfig + " did not register routees!", _system)

  _routees match {
    case x ⇒ _routees = x // volatile write to publish the route before sending messages
  }

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = {
    resize()

    val s = if (sender eq null) underlying.system.deadLetters else sender

    val msg = message match {
      case Broadcast(m) ⇒ m
      case m            ⇒ m
    }

    applyRoute(s, message) match {
      case Nil  ⇒ super.!(message)(s)
      case refs ⇒ refs foreach (p ⇒ p.recipient.!(msg)(p.sender))
    }
  }

  def resize() {
    for (r ← _props.routerConfig.resizer) {
      if (r.isTimeForResize(resizeCounter.getAndIncrement()) && resizeProgress.compareAndSet(false, true)) {
        try {
          r.resize(routeeProps, routeeProvider)
        } finally {
          resizeProgress.set(false)
        }
      }
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
 * be returned by `createActor()` should not send a message to itself in its
 * constructor or `preStart()` or publish its self reference from there: if
 * someone tries sending a message to that reference before the constructor of
 * RoutedActorRef has returned, there will be a `NullPointerException`!
 */
trait RouterConfig {

  def createRoute(routeeProps: Props, routeeProvider: RouteeProvider): Route

  def createRouteeProvider(context: ActorContext) = new RouteeProvider(context, resizer)

  def createActor(): Router = new Router {}

  /**
   * Overridable merge strategy, by default completely prefers “this” (i.e. no merge).
   */
  def withFallback(other: RouterConfig): RouterConfig = this

  protected def toAll(sender: ActorRef, routees: Iterable[ActorRef]): Iterable[Destination] = routees.map(Destination(sender, _))

  /**
   * Routers with dynamically resizable number of routees return the [[akka.routing.Resizer]]
   * to use.
   */
  def resizer: Option[Resizer] = None

}

/**
 * Factory and registry for routees of the router.
 * Uses `context.actorOf` to create routees from nrOfInstances property
 * and `context.actorFor` lookup routees from paths.
 */
class RouteeProvider(val context: ActorContext, val resizer: Option[Resizer]) {

  /**
   * Adds the routees to the router.
   * Adds death watch of the routees so that they are removed when terminated.
   * Not thread safe, but intended to be called from protected points, such as
   * `RouterConfig.createRoute` and `Resizer.resize`.
   */
  def registerRoutees(routees: IndexedSeq[ActorRef]): Unit = {
    routedRef.addRoutees(routees)
  }

  /**
   * Adds the routees to the router.
   * Adds death watch of the routees so that they are removed when terminated.
   * Not thread safe, but intended to be called from protected points, such as
   * `RouterConfig.createRoute` and `Resizer.resize`.
   * Java API.
   */
  def registerRoutees(routees: java.util.List[ActorRef]): Unit = {
    import scala.collection.JavaConverters._
    registerRoutees(routees.asScala.toIndexedSeq)
  }

  /**
   * Removes routees from the router. This method doesn't stop the routees.
   * Removes death watch of the routees.
   * Not thread safe, but intended to be called from protected points, such as
   * `Resizer.resize`.
   */
  def unregisterRoutees(routees: IndexedSeq[ActorRef]): Unit = {
    routedRef.removeRoutees(routees)
  }

  def createRoutees(props: Props, nrOfInstances: Int, routees: Iterable[String]): IndexedSeq[ActorRef] =
    (nrOfInstances, routees) match {
      case (x, Nil) if x <= 0 ⇒
        throw new IllegalArgumentException(
          "Must specify nrOfInstances or routees for [%s]" format context.self.path.toString)
      case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(props))(scala.collection.breakOut)
      case (_, xs)  ⇒ xs.map(context.actorFor(_))(scala.collection.breakOut)
    }

  def createAndRegisterRoutees(props: Props, nrOfInstances: Int, routees: Iterable[String]): Unit = {
    if (resizer.isEmpty) {
      registerRoutees(createRoutees(props, nrOfInstances, routees))
    }
  }

  /**
   * All routees of the router
   */
  def routees: IndexedSeq[ActorRef] = routedRef.routees

  private def routedRef = context.self.asInstanceOf[RoutedActorRef]

}

/**
 * Java API for a custom router factory.
 * @see akka.routing.RouterConfig
 */
abstract class CustomRouterConfig extends RouterConfig {
  override def createRoute(props: Props, routeeProvider: RouteeProvider): Route = {
    // as a bonus, this prevents closing of props and context in the returned Route PartialFunction
    val customRoute = createCustomRoute(props, routeeProvider)

    {
      case (sender, message) ⇒ customRoute.destinationsFor(sender, message)
    }
  }

  def createCustomRoute(props: Props, routeeProvider: RouteeProvider): CustomRoute

}

trait CustomRoute {
  def destinationsFor(sender: ActorRef, message: Any): java.lang.Iterable[Destination]
}

/**
 * Base trait for `Router` actors. Override `receive` to handle custom
 * messages which the corresponding [[akka.actor.RouterConfig]] lets
 * through by returning an empty route.
 */
trait Router extends Actor {

  val ref = self match {
    case x: RoutedActorRef ⇒ x
    case _                 ⇒ throw new ActorInitializationException("Router actor can only be used in RoutedActorRef", context.system)
  }

  final def receive = ({

    case Terminated(child) ⇒
      ref.removeRoutees(IndexedSeq(child))
      if (ref.routees.isEmpty) context.stop(self)

  }: Receive) orElse routerReceive

  def routerReceive: Receive = {
    case _ ⇒
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
 * Sending this message to a router will make it send back its currently used routees.
 * A RouterRoutees message is sent asynchronously to the "requester" containing information
 * about what routees the router is routing over.
 */
case object CurrentRoutees

/**
 * Message used to carry information about what routees the router is currently using.
 */
case class RouterRoutees(routees: Iterable[ActorRef])

/**
 * For every message sent to a router, its route determines a set of destinations,
 * where for each recipient a different sender may be specified; typically the
 * sender should match the sender of the original request, but e.g. the scatter-
 * gather router needs to receive the replies with an AskActorRef instead.
 */
case class Destination(sender: ActorRef, recipient: ActorRef)

/**
 * Routing configuration that indicates no routing; this is also the default
 * value which hence overrides the merge strategy in order to accept values
 * from lower-precendence sources. The decision whether or not to create a
 * router is taken in the LocalActorRefProvider based on Props.
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case object NoRouter extends RouterConfig {
  def createRoute(props: Props, routeeProvider: RouteeProvider): Route = null
  override def withFallback(other: RouterConfig): RouterConfig = other
}

/**
 * Router configuration which has no default, i.e. external configuration is required.
 */
case object FromConfig extends RouterConfig {
  def createRoute(props: Props, routeeProvider: RouteeProvider): Route =
    throw new ConfigurationException("router " + routeeProvider.context.self + " needs external configuration from file (e.g. application.conf)",
      routeeProvider.context.system)
}

/**
 * Java API: Router configuration which has no default, i.e. external configuration is required.
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class FromConfig() extends RouterConfig {
  def createRoute(props: Props, routeeProvider: RouteeProvider): Route =
    throw new ConfigurationException("router " + routeeProvider.context.self + " needs external configuration from file (e.g. application.conf)",
      routeeProvider.context.system)
}

object RoundRobinRouter {
  def apply(routees: Iterable[ActorRef]) = new RoundRobinRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): RoundRobinRouter = {
    import scala.collection.JavaConverters._
    apply(routees.asScala)
  }
}
/**
 * A Router that uses round-robin to select a connection. For concurrent calls, round robin is just a best effort.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' during instantiation they will
 * be ignored if the router is defined in the configuration file for the actor being used.
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class RoundRobinRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None)
  extends RouterConfig with RoundRobinLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = {
    this(routees = iterableAsScalaIterable(routeePaths))
  }

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))
}

trait RoundRobinLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(props: Props, routeeProvider: RouteeProvider): Route = {
    routeeProvider.createAndRegisterRoutees(props, nrOfInstances, routees)

    val next = new AtomicLong(0)

    def getNext(): ActorRef = {
      val _routees = routeeProvider.routees
      _routees((next.getAndIncrement % _routees.size).asInstanceOf[Int])
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routeeProvider.routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

object RandomRouter {
  def apply(routees: Iterable[ActorRef]) = new RandomRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): RandomRouter = {
    import scala.collection.JavaConverters._
    apply(routees.asScala)
  }
}
/**
 * A Router that randomly selects one of the target connections to send a message to.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' during instantiation they will
 * be ignored if the router is defined in the configuration file for the actor being used.
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class RandomRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None)
  extends RouterConfig with RandomLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = {
    this(routees = iterableAsScalaIterable(routeePaths))
  }

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))
}

trait RandomLike { this: RouterConfig ⇒
  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(props: Props, routeeProvider: RouteeProvider): Route = {
    routeeProvider.createAndRegisterRoutees(props, nrOfInstances, routees)

    def getNext(): ActorRef = {
      val _routees = routeeProvider.routees
      _routees(ThreadLocalRandom.current.nextInt(_routees.size))
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routeeProvider.routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

object SmallestMailboxRouter {
  def apply(routees: Iterable[ActorRef]) = new SmallestMailboxRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): SmallestMailboxRouter = {
    import scala.collection.JavaConverters._
    apply(routees.asScala)
  }
}
/**
 * A Router that tries to send to the non-suspended routee with fewest messages in mailbox.
 * The selection is done in this order:
 * <ul>
 * <li>pick any idle routee (not processing message) with empty mailbox</li>
 * <li>pick any routee with empty mailbox</li>
 * <li>pick routee with fewest pending messages in mailbox</li>
 * <li>pick any remote routee, remote actors are consider lowest priority,
 *     since their mailbox size is unknown</li>
 * </ul>
 *
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' during instantiation they will
 * be ignored if the router is defined in the configuration file for the actor being used.
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class SmallestMailboxRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None)
  extends RouterConfig with SmallestMailboxLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = {
    this(routees = iterableAsScalaIterable(routeePaths))
  }

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))
}

trait SmallestMailboxLike { this: RouterConfig ⇒

  import java.security.SecureRandom

  def nrOfInstances: Int

  def routees: Iterable[String]

  private val random = new ThreadLocal[SecureRandom] {
    override def initialValue = SecureRandom.getInstance("SHA1PRNG")
  }

  /**
   * Returns true if the actor is currently processing a message.
   * It will always return false for remote actors.
   * Method is exposed to subclasses to be able to implement custom
   * routers based on mailbox and actor internal state.
   */
  protected def isProcessingMessage(a: ActorRef): Boolean = a match {
    case x: LocalActorRef ⇒
      val cell = x.underlying
      cell.mailbox.isScheduled && cell.currentMessage != null
    case _ ⇒ false
  }

  /**
   * Returns true if the actor currently has any pending messages
   * in the mailbox, i.e. the mailbox is not empty.
   * It will always return false for remote actors.
   * Method is exposed to subclasses to be able to implement custom
   * routers based on mailbox and actor internal state.
   */
  protected def hasMessages(a: ActorRef): Boolean = a match {
    case x: LocalActorRef ⇒ x.underlying.mailbox.hasMessages
    case _                ⇒ false
  }

  /**
   * Returns true if the actor is currently suspended.
   * It will always return false for remote actors.
   * Method is exposed to subclasses to be able to implement custom
   * routers based on mailbox and actor internal state.
   */
  protected def isSuspended(a: ActorRef): Boolean = a match {
    case x: LocalActorRef ⇒
      val cell = x.underlying
      cell.mailbox.isSuspended
    case _ ⇒ false
  }

  /**
   * Returns the number of pending messages in the mailbox of the actor.
   * It will always return 0 for remote actors.
   * Method is exposed to subclasses to be able to implement custom
   * routers based on mailbox and actor internal state.
   */
  protected def numberOfMessages(a: ActorRef): Int = a match {
    case x: LocalActorRef ⇒ x.underlying.mailbox.numberOfMessages
    case _                ⇒ 0
  }

  def createRoute(props: Props, routeeProvider: RouteeProvider): Route = {
    routeeProvider.createAndRegisterRoutees(props, nrOfInstances, routees)

    def getNext(): ActorRef = {
      // non-local actors mailbox size is unknown, so consider them lowest priority
      val activeLocal = routeeProvider.routees collect { case l: LocalActorRef if !isSuspended(l) ⇒ l }
      // 1. anyone not processing message and with empty mailbox
      activeLocal.find(a ⇒ !isProcessingMessage(a) && !hasMessages(a)) getOrElse {
        // 2. anyone with empty mailbox
        activeLocal.find(a ⇒ !hasMessages(a)) getOrElse {
          // 3. sort on mailbox size
          activeLocal.sortBy(a ⇒ numberOfMessages(a)).headOption getOrElse {
            // 4. no locals, just pick one, random
            val _routees = routeeProvider.routees
            _routees(random.get.nextInt(_routees.size))
          }
        }
      }
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routeeProvider.routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

object BroadcastRouter {
  def apply(routees: Iterable[ActorRef]) = new BroadcastRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): BroadcastRouter = {
    import scala.collection.JavaConverters._
    apply(routees.asScala)
  }
}
/**
 * A Router that uses broadcasts a message to all its connections.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' during instantiation they will
 * be ignored if the router is defined in the configuration file for the actor being used.
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class BroadcastRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None)
  extends RouterConfig with BroadcastLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = {
    this(routees = iterableAsScalaIterable(routeePaths))
  }

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

}

trait BroadcastLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(props: Props, routeeProvider: RouteeProvider): Route = {
    routeeProvider.createAndRegisterRoutees(props, nrOfInstances, routees)

    {
      case (sender, message) ⇒ toAll(sender, routeeProvider.routees)
    }
  }
}

object ScatterGatherFirstCompletedRouter {
  def apply(routees: Iterable[ActorRef], within: Duration) = new ScatterGatherFirstCompletedRouter(routees = routees map (_.path.toString), within = within)

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef], within: Duration): ScatterGatherFirstCompletedRouter = {
    import scala.collection.JavaConverters._
    apply(routees.asScala, within)
  }
}
/**
 * Simple router that broadcasts the message to all routees, and replies with the first response.
 * <br/>
 * You have to defin the 'within: Duration' parameter (f.e: within = 10 seconds).
 * <br/>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br/>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' during instantiation they will
 * be ignored if the router is defined in the configuration file for the actor being used.
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class ScatterGatherFirstCompletedRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, within: Duration,
                                             override val resizer: Option[Resizer] = None)
  extends RouterConfig with ScatterGatherFirstCompletedLike {

  if (within <= Duration.Zero) throw new IllegalArgumentException(
    "[within: Duration] can not be zero or negative, was [" + within + "]")

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int, w: Duration) = {
    this(nrOfInstances = nr, within = w)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String], w: Duration) = {
    this(routees = iterableAsScalaIterable(routeePaths), within = w)
  }

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer, w: Duration) = this(resizer = Some(resizer), within = w)
}

trait ScatterGatherFirstCompletedLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def within: Duration

  def createRoute(props: Props, routeeProvider: RouteeProvider): Route = {
    routeeProvider.createAndRegisterRoutees(props, nrOfInstances, routees)

    {
      case (sender, message) ⇒
        val provider: ActorRefProvider = routeeProvider.context.asInstanceOf[ActorCell].systemImpl.provider
        val asker = akka.pattern.createAsker(provider, within)
        asker.result.pipeTo(sender)
        toAll(asker, routeeProvider.routees)
    }
  }
}

/**
 * Routers with dynamically resizable number of routees is implemented by providing a Resizer
 * implementation in [[akka.routing.RouterConfig]].
 */
trait Resizer {
  /**
   * Is it time for resizing. Typically implemented with modulo of nth message, but
   * could be based on elapsed time or something else. The messageCounter starts with 0
   * for the initial resize and continues with 1 for the first message. Make sure to perform
   * initial resize before first message (messageCounter == 0), because there is no guarantee
   * that resize will be done when concurrent messages are in play.
   */
  def isTimeForResize(messageCounter: Long): Boolean
  /**
   * Decide if the capacity of the router need to be changed. Will be invoked when `isTimeForResize`
   * returns true and no other resize is in progress.
   * Create and register more routees with `routeeProvider.registerRoutees(newRoutees)
   * or remove routees with `routeeProvider.unregisterRoutees(abandonedRoutees)` and
   * sending [[akka.actor.PoisonPill]] to them.
   */
  def resize(props: Props, routeeProvider: RouteeProvider)
}

case object DefaultResizer {
  def apply(resizerConfig: Config): DefaultResizer =
    DefaultResizer(
      lowerBound = resizerConfig.getInt("lower-bound"),
      upperBound = resizerConfig.getInt("upper-bound"),
      pressureThreshold = resizerConfig.getInt("pressure-threshold"),
      rampupRate = resizerConfig.getDouble("rampup-rate"),
      backoffThreshold = resizerConfig.getDouble("backoff-threshold"),
      backoffRate = resizerConfig.getDouble("backoff-rate"),
      stopDelay = Duration(resizerConfig.getMilliseconds("stop-delay"), TimeUnit.MILLISECONDS),
      messagesPerResize = resizerConfig.getInt("messages-per-resize"))
}

case class DefaultResizer(
  /**
   * The fewest number of routees the router should ever have.
   */
  lowerBound: Int = 1,
  /**
 * The most number of routees the router should ever have.
 * Must be greater than or equal to `lowerBound`.
 */
  upperBound: Int = 10,
  /**
 * Threshold to evaluate if routee is considered to be busy (under pressure).
 * Implementation depends on this value (default is 1).
 * <ul>
 * <li> 0:   number of routees currently processing a message.</li>
 * <li> 1:   number of routees currently processing a message has
 *           some messages in mailbox.</li>
 * <li> > 1: number of routees with at least the configured `pressureThreshold`
 *           messages in their mailbox. Note that estimating mailbox size of
 *           default UnboundedMailbox is O(N) operation.</li>
 * </ul>
 */
  pressureThreshold: Int = 1,
  /**
 * Percentage to increase capacity whenever all routees are busy.
 * For example, 0.2 would increase 20% (rounded up), i.e. if current
 * capacity is 6 it will request an increase of 2 more routees.
 */
  rampupRate: Double = 0.2,
  /**
 * Minimum fraction of busy routees before backing off.
 * For example, if this is 0.3, then we'll remove some routees only when
 * less than 30% of routees are busy, i.e. if current capacity is 10 and
 * 3 are busy then the capacity is unchanged, but if 2 or less are busy
 * the capacity is decreased.
 *
 * Use 0.0 or negative to avoid removal of routees.
 */
  backoffThreshold: Double = 0.3,
  /**
 * Fraction of routees to be removed when the resizer reaches the
 * backoffThreshold.
 * For example, 0.1 would decrease 10% (rounded up), i.e. if current
 * capacity is 9 it will request an decrease of 1 routee.
 */
  backoffRate: Double = 0.1,
  /**
 * When the resizer reduce the capacity the abandoned routee actors are stopped
 * with PoisonPill after this delay. The reason for the delay is to give concurrent
 * messages a chance to be placed in mailbox before sending PoisonPill.
 * Use 0 seconds to skip delay.
 */
  stopDelay: Duration = 1.second,
  /**
 * Number of messages between resize operation.
 * Use 1 to resize before each message.
 */
  messagesPerResize: Int = 10) extends Resizer {

  /**
   * Java API constructor for default values except bounds.
   */
  def this(lower: Int, upper: Int) = this(lowerBound = lower, upperBound = upper)

  if (lowerBound < 0) throw new IllegalArgumentException("lowerBound must be >= 0, was: [%s]".format(lowerBound))
  if (upperBound < 0) throw new IllegalArgumentException("upperBound must be >= 0, was: [%s]".format(upperBound))
  if (upperBound < lowerBound) throw new IllegalArgumentException("upperBound must be >= lowerBound, was: [%s] < [%s]".format(upperBound, lowerBound))
  if (rampupRate < 0.0) throw new IllegalArgumentException("rampupRate must be >= 0.0, was [%s]".format(rampupRate))
  if (backoffThreshold > 1.0) throw new IllegalArgumentException("backoffThreshold must be <= 1.0, was [%s]".format(backoffThreshold))
  if (backoffRate < 0.0) throw new IllegalArgumentException("backoffRate must be >= 0.0, was [%s]".format(backoffRate))
  if (messagesPerResize <= 0) throw new IllegalArgumentException("messagesPerResize must be > 0, was [%s]".format(messagesPerResize))

  def isTimeForResize(messageCounter: Long): Boolean = (messageCounter % messagesPerResize == 0)

  def resize(props: Props, routeeProvider: RouteeProvider) {
    val currentRoutees = routeeProvider.routees
    val requestedCapacity = capacity(currentRoutees)

    if (requestedCapacity > 0) {
      val newRoutees = routeeProvider.createRoutees(props, requestedCapacity, Nil)
      routeeProvider.registerRoutees(newRoutees)
    } else if (requestedCapacity < 0) {
      val (keep, abandon) = currentRoutees.splitAt(currentRoutees.length + requestedCapacity)
      routeeProvider.unregisterRoutees(abandon)
      delayedStop(routeeProvider.context.system.scheduler, abandon)
    }
  }

  /**
   * Give concurrent messages a chance to be placed in mailbox before
   * sending PoisonPill.
   */
  protected def delayedStop(scheduler: Scheduler, abandon: IndexedSeq[ActorRef]) {
    if (abandon.nonEmpty) {
      if (stopDelay <= Duration.Zero) {
        abandon foreach (_ ! PoisonPill)
      } else {
        scheduler.scheduleOnce(stopDelay) {
          abandon foreach (_ ! PoisonPill)
        }
      }
    }
  }

  /**
   * Returns the overall desired change in resizer capacity. Positive value will
   * add routees to the resizer. Negative value will remove routees from the
   * resizer.
   * @param routees The current actor in the resizer
   * @return the number of routees by which the resizer should be adjusted (positive, negative or zero)
   */
  def capacity(routees: IndexedSeq[ActorRef]): Int = {
    val currentSize = routees.size
    val delta = filter(pressure(routees), currentSize)
    val proposed = currentSize + delta

    if (proposed < lowerBound) delta + (lowerBound - proposed)
    else if (proposed > upperBound) delta - (proposed - upperBound)
    else delta
  }

  /**
   * Number of routees considered busy, or above 'pressure level'.
   *
   * Implementation depends on the value of `pressureThreshold`
   * (default is 1).
   * <ul>
   * <li> 0:   number of routees currently processing a message.</li>
   * <li> 1:   number of routees currently processing a message has
   *           some messages in mailbox.</li>
   * <li> > 1: number of routees with at least the configured `pressureThreshold`
   *           messages in their mailbox. Note that estimating mailbox size of
   *           default UnboundedMailbox is O(N) operation.</li>
   * </ul>
   *
   * @param routees the current resizer of routees
   * @return number of busy routees, between 0 and routees.size
   */
  def pressure(routees: IndexedSeq[ActorRef]): Int = {
    routees count {
      case a: LocalActorRef ⇒
        val cell = a.underlying
        pressureThreshold match {
          case 1          ⇒ cell.mailbox.isScheduled && cell.currentMessage != null
          case i if i < 1 ⇒ cell.mailbox.isScheduled && cell.currentMessage != null
          case threshold  ⇒ cell.mailbox.numberOfMessages >= threshold
        }
      case x ⇒
        false
    }
  }

  /**
   * This method can be used to smooth the capacity delta by considering
   * the current pressure and current capacity.
   *
   * @param pressure current number of busy routees
   * @param capacity current number of routees
   * @return proposed change in the capacity
   */
  def filter(pressure: Int, capacity: Int): Int = {
    rampup(pressure, capacity) + backoff(pressure, capacity)
  }

  /**
   * Computes a proposed positive (or zero) capacity delta using
   * the configured `rampupRate`.
   * @param pressure the current number of busy routees
   * @param capacity the current number of total routees
   * @return proposed increase in capacity
   */
  def rampup(pressure: Int, capacity: Int): Int =
    if (pressure < capacity) 0 else math.ceil(rampupRate * capacity) toInt

  /**
   * Computes a proposed negative (or zero) capacity delta using
   * the configured `backoffThreshold` and `backoffRate`
   * @param pressure the current number of busy routees
   * @param capacity the current number of total routees
   * @return proposed decrease in capacity (as a negative number)
   */
  def backoff(pressure: Int, capacity: Int): Int =
    if (backoffThreshold > 0.0 && backoffRate > 0.0 && capacity > 0 && pressure.toDouble / capacity < backoffThreshold)
      math.floor(-1.0 * backoffRate * capacity) toInt
    else 0

}

