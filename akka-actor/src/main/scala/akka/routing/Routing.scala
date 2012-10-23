/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import language.implicitConversions
import language.postfixOps
import akka.actor._
import scala.concurrent.duration._
import akka.ConfigurationException
import akka.pattern.pipe
import com.typesafe.config.Config
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean }
import java.util.concurrent.TimeUnit
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.dispatch.Dispatchers
import scala.annotation.tailrec
import concurrent.ExecutionContext

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to
 * send a message to on (or more) of these actors.
 */
private[akka] class RoutedActorRef(_system: ActorSystemImpl, _props: Props, _supervisor: InternalActorRef, _path: ActorPath)
  extends RepointableActorRef(_system, _props, _supervisor, _path) {

  // verify that a BalancingDispatcher is not used with a Router
  if (_props.routerConfig != NoRouter && _system.dispatchers.isBalancingDispatcher(_props.routerConfig.routerDispatcher)) {
    throw new ConfigurationException(
      "Configuration for " + this +
        " is invalid - you can not use a 'BalancingDispatcher' as a Router's dispatcher, you can however use it for the routees.")
  }

  _props.routerConfig.verifyConfig()

  override def newCell(old: Cell): Cell = new RoutedActorCell(system, this, props, supervisor, old.asInstanceOf[UnstartedCell].uid)

}

private[akka] class RoutedActorCell(_system: ActorSystemImpl, _ref: InternalActorRef, _props: Props, _supervisor: InternalActorRef, _uid: Int)
  extends ActorCell(
    _system,
    _ref,
    _props.copy(creator = () ⇒ _props.routerConfig.createActor(), dispatcher = _props.routerConfig.routerDispatcher),
    _supervisor) {

  private[akka] val routerConfig = _props.routerConfig
  private[akka] val resizeInProgress = new AtomicBoolean
  private val resizeCounter = new AtomicLong

  @volatile
  private var _routees: IndexedSeq[ActorRef] = IndexedSeq.empty[ActorRef] // this MUST be initialized during createRoute
  def routees = _routees

  @volatile
  private var _routeeProvider: RouteeProvider = _
  def routeeProvider = _routeeProvider

  val route = {
    val routeeProps = _props.copy(routerConfig = NoRouter)
    _routeeProvider = routerConfig.createRouteeProvider(this, routeeProps)
    val r = routerConfig.createRoute(routeeProvider)
    // initial resize, before message send
    routerConfig.resizer foreach { resizer ⇒
      if (resizer.isTimeForResize(resizeCounter.getAndIncrement()))
        resizer.resize(routeeProvider)
    }
    r
  }

  init(_uid, sendSupervise = false).start()

  /*
   * end of construction
   */

  def applyRoute(sender: ActorRef, message: Any): Iterable[Destination] = message match {
    case _: AutoReceivedMessage ⇒ Destination(self, self) :: Nil
    case CurrentRoutees ⇒
      sender ! RouterRoutees(_routees)
      Nil
    case _ ⇒
      if (route.isDefinedAt(sender, message)) route(sender, message)
      else Nil
  }

  /**
   * Adds the routees to existing routees.
   * Adds death watch of the routees so that they are removed when terminated.
   * Not thread safe, but intended to be called from protected points, such as
   * `RouterConfig.createRoute` and `Resizer.resize`
   */
  private[akka] def addRoutees(newRoutees: Iterable[ActorRef]): Unit = {
    _routees = _routees ++ newRoutees
    // subscribe to Terminated messages for all route destinations, to be handled by Router actor
    newRoutees foreach watch
  }

  /**
   * Adds the routees to existing routees.
   * Removes death watch of the routees. Doesn't stop the routees.
   * Not thread safe, but intended to be called from protected points, such as
   * `Resizer.resize`
   */
  private[akka] def removeRoutees(abandonedRoutees: Iterable[ActorRef]): Unit = {
    _routees = abandonedRoutees.foldLeft(_routees) { (xs, x) ⇒ unwatch(x); xs.filterNot(_ == x) }
  }

  override def tell(message: Any, sender: ActorRef): Unit = {
    resize()

    val s = if (sender eq null) system.deadLetters else sender

    val msg = message match {
      case wrapped: RouterEnvelope ⇒ wrapped.message
      case m                       ⇒ m
    }

    applyRoute(s, message) match {
      case Destination(_, x) :: Nil if x == self ⇒ super.tell(message, s)
      case refs ⇒
        refs foreach (p ⇒
          if (p.recipient == self) super.tell(msg, p.sender)
          else p.recipient.!(msg)(p.sender))
    }
  }

  def resize(): Unit = {
    for (r ← routerConfig.resizer) {
      if (r.isTimeForResize(resizeCounter.getAndIncrement()) && resizeInProgress.compareAndSet(false, true))
        super.tell(Router.Resize, self)
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

  /**
   * Implement the routing logic by returning a partial function of
   * partial function from (sender, message) to a set of destinations.
   * This `Route` will be applied for each incoming message.
   *
   * When `createRoute` is called the routees should also be registered,
   * typically by using `createRoutees` or `registerRouteesFor` of the
   * supplied `RouteeProvider`.
   */
  def createRoute(routeeProvider: RouteeProvider): Route

  /**
   * The `RouteeProvider` responsible for creating or
   * looking up routees. It's used in `createRoute` to register routees,
   * and also from [[akka.routing.Resizer]].
   */
  def createRouteeProvider(context: ActorContext, routeeProps: Props): RouteeProvider =
    new RouteeProvider(context, routeeProps, resizer)

  /**
   * The router "head" actor.
   */
  def createActor(): Router = new Router {
    override def supervisorStrategy: SupervisorStrategy = RouterConfig.this.supervisorStrategy
  }

  /**
   * SupervisorStrategy for the created Router actor.
   */
  def supervisorStrategy: SupervisorStrategy

  /**
   * Dispatcher ID to use for running the “head” actor, i.e. the [[akka.routing.Router]].
   */
  def routerDispatcher: String

  /**
   * Overridable merge strategy, by default completely prefers “this” (i.e. no merge).
   */
  def withFallback(other: RouterConfig): RouterConfig = this

  protected def toAll(sender: ActorRef, routees: Iterable[ActorRef]): Iterable[Destination] =
    routees.map(Destination(sender, _))

  /**
   * Routers with dynamically resizable number of routees return the [[akka.routing.Resizer]]
   * to use.
   */
  def resizer: Option[Resizer] = None

  /**
   * Check that everything is there which is needed. Called in constructor of RoutedActorRef to fail early.
   */
  def verifyConfig(): Unit = {}

}

/**
 * Factory and registry for routees of the router.
 * Uses `context.actorOf` to create routees from nrOfInstances property
 * and `context.actorFor` lookup routees from paths.
 */
class RouteeProvider(val context: ActorContext, val routeeProps: Props, val resizer: Option[Resizer]) {

  import scala.collection.JavaConverters._

  /**
   * Adds the routees to the router.
   * Adds death watch of the routees so that they are removed when terminated.
   * Not thread safe, but intended to be called from protected points, such as
   * `RouterConfig.createRoute` and `Resizer.resize`.
   */
  def registerRoutees(routees: Iterable[ActorRef]): Unit = routedCell.addRoutees(routees)

  /**
   * Adds the routees to the router.
   * Adds death watch of the routees so that they are removed when terminated.
   * Not thread safe, but intended to be called from protected points, such as
   * `RouterConfig.createRoute` and `Resizer.resize`.
   * Java API.
   */
  def registerRoutees(routees: java.lang.Iterable[ActorRef]): Unit = registerRoutees(routees.asScala)

  /**
   * Removes routees from the router. This method doesn't stop the routees.
   * Removes death watch of the routees.
   * Not thread safe, but intended to be called from protected points, such as
   * `Resizer.resize`.
   */
  def unregisterRoutees(routees: Iterable[ActorRef]): Unit = routedCell.removeRoutees(routees)

  /**
   * Removes routees from the router. This method doesn't stop the routees.
   * Removes death watch of the routees.
   * Not thread safe, but intended to be called from protected points, such as
   * `Resizer.resize`.
   * JAVA API
   */
  def unregisterRoutees(routees: java.lang.Iterable[ActorRef]): Unit = unregisterRoutees(routees.asScala)

  /**
   * Looks up routes with specified paths and registers them.
   */
  def registerRouteesFor(paths: Iterable[String]): Unit = registerRoutees(paths.map(context.actorFor(_)))

  /**
   * Looks up routes with specified paths and registers them.
   * JAVA API
   */
  def registerRouteesFor(paths: java.lang.Iterable[String]): Unit = registerRouteesFor(paths.asScala)

  /**
   * Creates new routees from specified `Props` and registers them.
   */
  def createRoutees(nrOfInstances: Int): Unit = {
    if (nrOfInstances <= 0) throw new IllegalArgumentException(
      "Must specify nrOfInstances or routees for [%s]" format context.self.path.toString)
    else
      registerRoutees(IndexedSeq.fill(nrOfInstances)(context.actorOf(routeeProps)))
  }

  /**
   * Remove specified number of routees by unregister them
   * and sending [[akka.actor.PoisonPill]] after the specified delay.
   * The reason for the delay is to give concurrent messages a chance to be
   * placed in mailbox before sending PoisonPill.
   */
  def removeRoutees(nrOfInstances: Int, stopDelay: FiniteDuration): Unit = {
    if (nrOfInstances <= 0) {
      throw new IllegalArgumentException("Expected positive nrOfInstances, got [%s]".format(nrOfInstances))
    } else if (nrOfInstances > 0) {
      val currentRoutees = routees
      val abandon = currentRoutees.drop(currentRoutees.length - nrOfInstances)
      unregisterRoutees(abandon)
      delayedStop(context.system.scheduler, abandon, stopDelay)
    }
  }

  /**
   * Give concurrent messages a chance to be placed in mailbox before
   * sending PoisonPill.
   */
  protected def delayedStop(scheduler: Scheduler, abandon: Iterable[ActorRef], stopDelay: FiniteDuration): Unit = {
    if (abandon.nonEmpty) {
      if (stopDelay <= Duration.Zero) {
        abandon foreach (_ ! PoisonPill)
      } else {
        import context.dispatcher
        // Iterable could potentially be mutable
        val localAbandon = abandon.toIndexedSeq
        scheduler.scheduleOnce(stopDelay) {
          localAbandon foreach (_ ! PoisonPill)
        }
      }
    }
  }

  /**
   * All routees of the router
   */
  def routees: IndexedSeq[ActorRef] = routedCell.routees

  /**
   * All routees of the router
   * JAVA API
   */
  def getRoutees(): java.util.List[ActorRef] = routees.asJava

  private def routedCell = context.asInstanceOf[RoutedActorCell]
}

/**
 * Java API for a custom router factory.
 * @see akka.routing.RouterConfig
 */
abstract class CustomRouterConfig extends RouterConfig {
  override def createRoute(routeeProvider: RouteeProvider): Route = {
    // as a bonus, this prevents closing of props and context in the returned Route PartialFunction
    val customRoute = createCustomRoute(routeeProvider)

    {
      case (sender, message) ⇒ customRoute.destinationsFor(sender, message).asScala
    }
  }

  def createCustomRoute(routeeProvider: RouteeProvider): CustomRoute

}

trait CustomRoute {
  def destinationsFor(sender: ActorRef, message: Any): java.lang.Iterable[Destination]
}

/**
 * Base trait for `Router` actors. Override `receive` to handle custom
 * messages which the corresponding [[akka.routing.RouterConfig]] lets
 * through by returning an empty route.
 */
trait Router extends Actor {

  val ref = context match {
    case x: RoutedActorCell ⇒ x
    case _                  ⇒ throw ActorInitializationException("Router actor can only be used in RoutedActorRef, not in " + context.getClass)
  }

  final def receive = ({

    case Router.Resize ⇒
      val ab = ref.resizeInProgress
      if (ab.get) try ref.routerConfig.resizer foreach (_.resize(ref.routeeProvider)) finally ab.set(false)

    case Terminated(child) ⇒
      ref.removeRoutees(IndexedSeq(child))
      if (ref.routees.isEmpty) context.stop(self)

  }: Receive) orElse routerReceive

  def routerReceive: Receive = Actor.emptyBehavior

  override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
    // do not scrap children
  }
}

/**
 * INTERNAL API
 */
private object Router {
  @SerialVersionUID(1L)
  case object Resize

  val defaultSupervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }
}

/**
 * Used to broadcast a message to all connections in a router; only the
 * contained message will be forwarded, i.e. the `Broadcast(...)`
 * envelope will be stripped off.
 *
 * Router implementations may choose to handle this message differently.
 */
@SerialVersionUID(1L)
case class Broadcast(message: Any) extends RouterEnvelope

/**
 * Only the contained message will be forwarded to the
 * destination, i.e. the envelope will be stripped off.
 */
trait RouterEnvelope {
  def message: Any
}

/**
 * Sending this message to a router will make it send back its currently used routees.
 * A RouterRoutees message is sent asynchronously to the "requester" containing information
 * about what routees the router is routing over.
 */
abstract class CurrentRoutees
@SerialVersionUID(1L)
case object CurrentRoutees extends CurrentRoutees {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * Message used to carry information about what routees the router is currently using.
 */
@SerialVersionUID(1L)
case class RouterRoutees(routees: Iterable[ActorRef])

/**
 * For every message sent to a router, its route determines a set of destinations,
 * where for each recipient a different sender may be specified; typically the
 * sender should match the sender of the original request, but e.g. the scatter-
 * gather router needs to receive the replies with an AskActorRef instead.
 */
@SerialVersionUID(1L)
case class Destination(sender: ActorRef, recipient: ActorRef)

/**
 * Routing configuration that indicates no routing; this is also the default
 * value which hence overrides the merge strategy in order to accept values
 * from lower-precedence sources. The decision whether or not to create a
 * router is taken in the LocalActorRefProvider based on Props.
 */
@SerialVersionUID(1L)
abstract class NoRouter extends RouterConfig
case object NoRouter extends NoRouter {
  def createRoute(routeeProvider: RouteeProvider): Route = throw new UnsupportedOperationException("NoRouter does not createRoute")
  def routerDispatcher: String = throw new UnsupportedOperationException("NoRouter has no dispatcher")
  def supervisorStrategy = throw new UnsupportedOperationException("NoRouter has no strategy")
  override def withFallback(other: RouterConfig): RouterConfig = other

  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * Router configuration which has no default, i.e. external configuration is required.
 */
case object FromConfig extends FromConfig {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
  @inline final def apply(routerDispatcher: String = Dispatchers.DefaultDispatcherId) = new FromConfig(routerDispatcher)
  @inline final def unapply(fc: FromConfig): Option[String] = Some(fc.routerDispatcher)
}

/**
 * Java API: Router configuration which has no default, i.e. external configuration is required.
 *
 * This can be used when the dispatcher to be used for the head Router needs to be configured
 * (defaults to default-dispatcher).
 */
@SerialVersionUID(1L)
class FromConfig(val routerDispatcher: String = Dispatchers.DefaultDispatcherId)
  extends RouterConfig
  with Serializable {

  def this() = this(Dispatchers.DefaultDispatcherId)

  override def verifyConfig(): Unit =
    throw new ConfigurationException("router needs external configuration from file (e.g. application.conf)")

  def createRoute(routeeProvider: RouteeProvider): Route = null

  def supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy
}

object RoundRobinRouter {
  /**
   * Creates a new RoundRobinRouter, routing to the specified routees
   */
  def apply(routees: Iterable[ActorRef]): RoundRobinRouter =
    new RoundRobinRouter(routees = routees map (_.path.toString))

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
 * <h1>Supervision Setup</h1>
 *
 * The router creates a “head” actor which supervises and/or monitors the
 * routees. Instances are created as children of this actor, hence the
 * children are not supervised by the parent of the router. Common choices are
 * to always escalate (meaning that fault handling is always applied to all
 * children simultaneously; this is the default) or use the parent’s strategy,
 * which will result in routed children being treated individually, but it is
 * possible as well to use Routers to give different supervisor strategies to
 * different groups of children.
 *
 * {{{
 * class MyActor extends Actor {
 *   override val supervisorStrategy = ...
 *
 *   val poolAsAWhole = context.actorOf(Props[SomeActor].withRouter(RoundRobinRouter(5)))
 *
 *   val poolIndividuals = context.actorOf(Props[SomeActor].withRouter(
 *     RoundRobinRouter(5, supervisorStrategy = this.supervisorStrategy)))
 *
 *   val specialChild = context.actorOf(Props[SomeActor].withRouter(
 *     RoundRobinRouter(5, supervisorStrategy = OneForOneStrategy() {
 *       ...
 *     })))
 * }
 * }}}
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
case class RoundRobinRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                            val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                            val supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy)
  extends RouterConfig with RoundRobinLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = routeePaths.asScala)

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String): RoundRobinRouter = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): RoundRobinRouter = copy(supervisorStrategy = strategy)

  /**
   * Uses the resizer of the given Routerconfig if this RouterConfig
   * doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = {
    if (this.resizer.isEmpty && other.resizer.isDefined) copy(resizer = other.resizer)
    else this
  }
}

/**
 * The core pieces of the routing logic is located in this
 * trait to be able to extend.
 */
trait RoundRobinLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    val next = new AtomicLong(0)

    def getNext(): ActorRef = {
      val currentRoutees = routeeProvider.routees
      if (currentRoutees.isEmpty) routeeProvider.context.system.deadLetters
      else currentRoutees((next.getAndIncrement % currentRoutees.size).asInstanceOf[Int])
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
  /**
   * Creates a new RandomRouter, routing to the specified routees
   */
  def apply(routees: Iterable[ActorRef]): RandomRouter = new RandomRouter(routees = routees map (_.path.toString))

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
 * <h1>Supervision Setup</h1>
 *
 * The router creates a “head” actor which supervises and/or monitors the
 * routees. Instances are created as children of this actor, hence the
 * children are not supervised by the parent of the router. Common choices are
 * to always escalate (meaning that fault handling is always applied to all
 * children simultaneously; this is the default) or use the parent’s strategy,
 * which will result in routed children being treated individually, but it is
 * possible as well to use Routers to give different supervisor strategies to
 * different groups of children.
 *
 * {{{
 * class MyActor extends Actor {
 *   override val supervisorStrategy = ...
 *
 *   val poolAsAWhole = context.actorOf(Props[SomeActor].withRouter(RandomRouter(5)))
 *
 *   val poolIndividuals = context.actorOf(Props[SomeActor].withRouter(
 *     RandomRouter(5, supervisorStrategy = this.supervisorStrategy)))
 *
 *   val specialChild = context.actorOf(Props[SomeActor].withRouter(
 *     RandomRouter(5, supervisorStrategy = OneForOneStrategy() {
 *       ...
 *     })))
 * }
 * }}}
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
case class RandomRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                        val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                        val supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy)
  extends RouterConfig with RandomLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = routeePaths.asScala)

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String): RandomRouter = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): RandomRouter = copy(supervisorStrategy = strategy)

  /**
   * Uses the resizer of the given Routerconfig if this RouterConfig
   * doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = {
    if (this.resizer.isEmpty && other.resizer.isDefined) copy(resizer = other.resizer)
    else this
  }
}

/**
 * The core pieces of the routing logic is located in this
 * trait to be able to extend.
 */
trait RandomLike { this: RouterConfig ⇒
  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    def getNext(): ActorRef = {
      val currentRoutees = routeeProvider.routees
      if (currentRoutees.isEmpty) routeeProvider.context.system.deadLetters
      else currentRoutees(ThreadLocalRandom.current.nextInt(currentRoutees.size))
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
  /**
   * Creates a new SmallestMailboxRouter, routing to the specified routees
   */
  def apply(routees: Iterable[ActorRef]): SmallestMailboxRouter =
    new SmallestMailboxRouter(routees = routees map (_.path.toString))

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
 * <h1>Supervision Setup</h1>
 *
 * The router creates a “head” actor which supervises and/or monitors the
 * routees. Instances are created as children of this actor, hence the
 * children are not supervised by the parent of the router. Common choices are
 * to always escalate (meaning that fault handling is always applied to all
 * children simultaneously; this is the default) or use the parent’s strategy,
 * which will result in routed children being treated individually, but it is
 * possible as well to use Routers to give different supervisor strategies to
 * different groups of children.
 *
 * {{{
 * class MyActor extends Actor {
 *   override val supervisorStrategy = ...
 *
 *   val poolAsAWhole = context.actorOf(Props[SomeActor].withRouter(SmallestMailboxRouter(5)))
 *
 *   val poolIndividuals = context.actorOf(Props[SomeActor].withRouter(
 *     SmallestMailboxRouter(5, supervisorStrategy = this.supervisorStrategy)))
 *
 *   val specialChild = context.actorOf(Props[SomeActor].withRouter(
 *     SmallestMailboxRouter(5, supervisorStrategy = OneForOneStrategy() {
 *       ...
 *     })))
 * }
 * }}}
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
case class SmallestMailboxRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                                 val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                 val supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy)
  extends RouterConfig with SmallestMailboxLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = routeePaths.asScala)

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String): SmallestMailboxRouter = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): SmallestMailboxRouter = copy(supervisorStrategy = strategy)

  /**
   * Uses the resizer of the given Routerconfig if this RouterConfig
   * doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = {
    if (this.resizer.isEmpty && other.resizer.isDefined) copy(resizer = other.resizer)
    else this
  }
}

/**
 * The core pieces of the routing logic is located in this
 * trait to be able to extend.
 */
trait SmallestMailboxLike { this: RouterConfig ⇒
  def nrOfInstances: Int

  def routees: Iterable[String]

  /**
   * Returns true if the actor is currently processing a message.
   * It will always return false for remote actors.
   * Method is exposed to subclasses to be able to implement custom
   * routers based on mailbox and actor internal state.
   */
  protected def isProcessingMessage(a: ActorRef): Boolean = a match {
    case x: ActorRefWithCell ⇒
      x.underlying match {
        case cell: ActorCell ⇒ cell.mailbox.isScheduled && cell.currentMessage != null
        case _               ⇒ false
      }
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
    case x: ActorRefWithCell ⇒ x.underlying.hasMessages
    case _                   ⇒ false
  }

  /**
   * Returns true if the actor is currently suspended.
   * It will always return false for remote actors.
   * Method is exposed to subclasses to be able to implement custom
   * routers based on mailbox and actor internal state.
   */
  protected def isSuspended(a: ActorRef): Boolean = a match {
    case x: ActorRefWithCell ⇒
      x.underlying match {
        case cell: ActorCell ⇒ cell.mailbox.isSuspended
        case _               ⇒ true
      }
    case _ ⇒ false
  }

  /**
   * Returns the number of pending messages in the mailbox of the actor.
   * It will always return 0 for remote actors.
   * Method is exposed to subclasses to be able to implement custom
   * routers based on mailbox and actor internal state.
   */
  protected def numberOfMessages(a: ActorRef): Int = a match {
    case x: ActorRefWithCell ⇒ x.underlying.numberOfMessages
    case _                   ⇒ 0
  }

  def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    // Worst-case a 2-pass inspection with mailbox size checking done on second pass, and only until no one empty is found.
    // Lowest score wins, score 0 is autowin
    // If no actor with score 0 is found, it will return that, or if it is terminated, a random of the entire set.
    //   Why? Well, in case we had 0 viable actors and all we got was the default, which is the DeadLetters, anything else is better.
    // Order of interest, in ascending priority:
    // 1. The DeadLetterActorRef
    // 2. A Suspended ActorRef
    // 3. An ActorRef with unknown mailbox size but with one message being processed
    // 4. An ActorRef with unknown mailbox size that isn't processing anything
    // 5. An ActorRef with a known mailbox size
    // 6. An ActorRef without any messages
    @tailrec def getNext(targets: IndexedSeq[ActorRef] = routeeProvider.routees,
                         proposedTarget: ActorRef = routeeProvider.context.system.deadLetters,
                         currentScore: Long = Long.MaxValue,
                         at: Int = 0,
                         deep: Boolean = false): ActorRef =
      if (targets.isEmpty)
        routeeProvider.context.system.deadLetters
      else if (at >= targets.size) {
        if (deep) {
          if (proposedTarget.isTerminated) targets(ThreadLocalRandom.current.nextInt(targets.size)) else proposedTarget
        } else getNext(targets, proposedTarget, currentScore, 0, deep = true)
      } else {
        val target = targets(at)
        val newScore: Long =
          if (isSuspended(target)) Long.MaxValue - 1 else { //Just about better than the DeadLetters
            (if (isProcessingMessage(target)) 1l else 0l) +
              (if (!hasMessages(target)) 0l else { //Race between hasMessages and numberOfMessages here, unfortunate the numberOfMessages returns 0 if unknown
                val noOfMsgs: Long = if (deep) numberOfMessages(target) else 0
                if (noOfMsgs > 0) noOfMsgs else Long.MaxValue - 3 //Just better than a suspended actorref
              })
          }

        if (newScore == 0) target
        else if (newScore < 0 || newScore >= currentScore) getNext(targets, proposedTarget, currentScore, at + 1, deep)
        else getNext(targets, target, newScore, at + 1, deep)
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
  /**
   * Creates a new BroadcastRouter, routing to the specified routees
   */
  def apply(routees: Iterable[ActorRef]): BroadcastRouter = new BroadcastRouter(routees = routees map (_.path.toString))

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
 * <h1>Supervision Setup</h1>
 *
 * The router creates a “head” actor which supervises and/or monitors the
 * routees. Instances are created as children of this actor, hence the
 * children are not supervised by the parent of the router. Common choices are
 * to always escalate (meaning that fault handling is always applied to all
 * children simultaneously; this is the default) or use the parent’s strategy,
 * which will result in routed children being treated individually, but it is
 * possible as well to use Routers to give different supervisor strategies to
 * different groups of children.
 *
 * {{{
 * class MyActor extends Actor {
 *   override val supervisorStrategy = ...
 *
 *   val poolAsAWhole = context.actorOf(Props[SomeActor].withRouter(BroadcastRouter(5)))
 *
 *   val poolIndividuals = context.actorOf(Props[SomeActor].withRouter(
 *     BroadcastRouter(5, supervisorStrategy = this.supervisorStrategy)))
 *
 *   val specialChild = context.actorOf(Props[SomeActor].withRouter(
 *     BroadcastRouter(5, supervisorStrategy = OneForOneStrategy() {
 *       ...
 *     })))
 * }
 * }}}
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
case class BroadcastRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                           val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                           val supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy)
  extends RouterConfig with BroadcastLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = routeePaths.asScala)

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String): BroadcastRouter = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): BroadcastRouter = copy(supervisorStrategy = strategy)

  /**
   * Uses the resizer of the given Routerconfig if this RouterConfig
   * doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = {
    if (this.resizer.isEmpty && other.resizer.isDefined) copy(resizer = other.resizer)
    else this
  }
}

/**
 * The core pieces of the routing logic is located in this
 * trait to be able to extend.
 */
trait BroadcastLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    {
      case (sender, message) ⇒ toAll(sender, routeeProvider.routees)
    }
  }
}

object ScatterGatherFirstCompletedRouter {
  /**
   * Creates a new ScatterGatherFirstCompletedRouter, routing to the specified routees, timing out after the specified Duration
   */
  def apply(routees: Iterable[ActorRef], within: FiniteDuration): ScatterGatherFirstCompletedRouter =
    new ScatterGatherFirstCompletedRouter(routees = routees map (_.path.toString), within = within)

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef], within: FiniteDuration): ScatterGatherFirstCompletedRouter = {
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
 * <h1>Supervision Setup</h1>
 *
 * The router creates a “head” actor which supervises and/or monitors the
 * routees. Instances are created as children of this actor, hence the
 * children are not supervised by the parent of the router. Common choices are
 * to always escalate (meaning that fault handling is always applied to all
 * children simultaneously; this is the default) or use the parent’s strategy,
 * which will result in routed children being treated individually, but it is
 * possible as well to use Routers to give different supervisor strategies to
 * different groups of children.
 *
 * {{{
 * class MyActor extends Actor {
 *   override val supervisorStrategy = ...
 *
 *   val poolAsAWhole = context.actorOf(Props[SomeActor].withRouter(ScatterGatherFirstCompletedRouter(5)))
 *
 *   val poolIndividuals = context.actorOf(Props[SomeActor].withRouter(
 *     ScatterGatherFirstCompletedRouter(5, supervisorStrategy = this.supervisorStrategy)))
 *
 *   val specialChild = context.actorOf(Props[SomeActor].withRouter(
 *     ScatterGatherFirstCompletedRouter(5, supervisorStrategy = OneForOneStrategy() {
 *       ...
 *     })))
 * }
 * }}}
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
case class ScatterGatherFirstCompletedRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, within: FiniteDuration,
                                             override val resizer: Option[Resizer] = None,
                                             val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                             val supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy)
  extends RouterConfig with ScatterGatherFirstCompletedLike {

  if (within <= Duration.Zero) throw new IllegalArgumentException(
    "[within: Duration] can not be zero or negative, was [" + within + "]")

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int, w: FiniteDuration) = this(nrOfInstances = nr, within = w)

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String], w: FiniteDuration) = this(routees = routeePaths.asScala, within = w)

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer, w: FiniteDuration) = this(resizer = Some(resizer), within = w)

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String) = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy) = copy(supervisorStrategy = strategy)

  /**
   * Uses the resizer of the given Routerconfig if this RouterConfig
   * doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = {
    if (this.resizer.isEmpty && other.resizer.isDefined) copy(resizer = other.resizer)
    else this
  }
}

/**
 * The core pieces of the routing logic is located in this
 * trait to be able to extend.
 */
trait ScatterGatherFirstCompletedLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def within: FiniteDuration

  def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    {
      case (sender, message) ⇒
        val provider: ActorRefProvider = routeeProvider.context.asInstanceOf[ActorCell].systemImpl.provider
        import routeeProvider.context.dispatcher
        val asker = akka.pattern.PromiseActorRef(provider, within)
        asker.result.future.pipeTo(sender)
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
   *
   * CAUTION: this method is invoked from the thread which tries to send a
   * message to the pool, i.e. the ActorRef.!() method, hence it may be called
   * concurrently.
   */
  def isTimeForResize(messageCounter: Long): Boolean

  /**
   * Decide if the capacity of the router need to be changed. Will be invoked when `isTimeForResize`
   * returns true and no other resize is in progress.
   * Create and register more routees with `routeeProvider.registerRoutees(newRoutees)
   * or remove routees with `routeeProvider.unregisterRoutees(abandonedRoutees)` and
   * sending [[akka.actor.PoisonPill]] to them.
   *
   * This method is invoked only in the context of the Router actor in order to safely
   * create/stop children.
   */
  def resize(routeeProvider: RouteeProvider): Unit
}

case object DefaultResizer {

  /**
   * Creates a new DefaultResizer from the given configuration
   */
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

//FIXME DOCUMENT ME
@SerialVersionUID(1L)
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
  stopDelay: FiniteDuration = 1.second,
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

  def resize(routeeProvider: RouteeProvider): Unit = {
    val currentRoutees = routeeProvider.routees
    val requestedCapacity = capacity(currentRoutees)

    if (requestedCapacity > 0) routeeProvider.createRoutees(requestedCapacity)
    else if (requestedCapacity < 0) routeeProvider.removeRoutees(-requestedCapacity, stopDelay)
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
    val press = pressure(routees)
    val delta = filter(press, currentSize)
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
      case a: ActorRefWithCell ⇒
        a.underlying match {
          case cell: ActorCell ⇒
            pressureThreshold match {
              case 1          ⇒ cell.mailbox.isScheduled && cell.mailbox.hasMessages
              case i if i < 1 ⇒ cell.mailbox.isScheduled && cell.currentMessage != null
              case threshold  ⇒ cell.mailbox.numberOfMessages >= threshold
            }
          case cell ⇒
            pressureThreshold match {
              case 1          ⇒ cell.hasMessages
              case i if i < 1 ⇒ true // unstarted cells are always busy, for example
              case threshold  ⇒ cell.numberOfMessages >= threshold
            }
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
  def filter(pressure: Int, capacity: Int): Int = rampup(pressure, capacity) + backoff(pressure, capacity)

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

