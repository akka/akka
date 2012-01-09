/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.actor._
import akka.dispatch.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import akka.util.{ Duration, Timeout }
import akka.util.duration._
import akka.config.ConfigurationException
import scala.collection.JavaConversions.iterableAsScalaIterable

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

  @volatile
  private var _routees: IndexedSeq[ActorRef] = IndexedSeq.empty[ActorRef] // this MUST be initialized during createRoute
  def routees = _routees

  def addRoutees(newRoutees: IndexedSeq[ActorRef]) {
    _routees = _routees ++ newRoutees
    // subscribe to Terminated messages for all route destinations, to be handled by Router actor
    newRoutees foreach underlying.watch
  }

  def removeRoutees(abandonedRoutees: IndexedSeq[ActorRef]) {
    _routees = _routees filterNot (x ⇒ abandonedRoutees.contains(x))
    abandonedRoutees foreach underlying.unwatch
  }

  val route = _props.routerConfig.createRoute(routeeProps, actorContext, this)

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

  if (_props.routerConfig.pool.isEmpty && _routees.isEmpty)
    throw new ActorInitializationException("router " + _props.routerConfig + " did not register routees!")

  _routees match {
    case x ⇒ _routees = x // volatile write to publish the route before sending messages
  }

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = {
    _props.routerConfig.resizePool(routeeProps, actorContext, routees)

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

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = {
    _props.routerConfig.resizePool(routeeProps, actorContext, routees)
    super.?(message)(timeout)
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

  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route

  def createActor(): Router = new Router {}

  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig = {
    deploy match {
      case Some(Deploy(_, _, _, NoRouter, _)) ⇒ this
      case Some(Deploy(_, _, _, r, _))        ⇒ r
      case _                                  ⇒ this
    }
  }

  protected def toAll(sender: ActorRef, routees: Iterable[ActorRef]): Iterable[Destination] = routees.map(Destination(sender, _))

  def createRoutees(props: Props, context: ActorContext, nrOfInstances: Int, routees: Iterable[String]): IndexedSeq[ActorRef] = (nrOfInstances, routees) match {
    case (0, Nil) ⇒ throw new IllegalArgumentException("Insufficient information - missing configuration.")
    case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(props))(scala.collection.breakOut)
    case (_, xs)  ⇒ xs.map(context.actorFor(_))(scala.collection.breakOut)
  }

  protected def createAndRegisterRoutees(props: Props, context: ActorContext, nrOfInstances: Int, routees: Iterable[String]): Unit = {
    pool match {
      case None    ⇒ registerRoutees(context, createRoutees(props, context, nrOfInstances, routees))
      case Some(p) ⇒ resizePool(props, context, context.self.asInstanceOf[RoutedActorRef].routees)
    }
  }

  /**
   * Adds new routees to the router.
   */
  def registerRoutees(context: ActorContext, routees: IndexedSeq[ActorRef]): Unit = {
    context.self.asInstanceOf[RoutedActorRef].addRoutees(routees)
  }

  /**
   * Removes routees from the router. This method doesn't stop the routees.
   */
  def unregisterRoutees(context: ActorContext, routees: IndexedSeq[ActorRef]): Unit = {
    context.self.asInstanceOf[RoutedActorRef].removeRoutees(routees)
  }

  def pool: Option[RouterPool] = None

  private val resizePoolInProgress = new AtomicBoolean

  def resizePool(props: Props, context: ActorContext, currentRoutees: IndexedSeq[ActorRef]) {
    for (p ← pool) {
      if (resizePoolInProgress.compareAndSet(false, true)) {
        try {
          p.resize(props, context, currentRoutees, this)
        } finally {
          resizePoolInProgress.set(false)
        }
      }
    }
  }

}

/**
 * Java API for a custom router factory.
 * @see akka.routing.RouterConfig
 */
abstract class CustomRouterConfig extends RouterConfig {
  override def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    val customRoute = createCustomRoute(props, context, ref)

    {
      case (sender, message) ⇒ customRoute.destinationsFor(sender, message)
    }
  }

  def createCustomRoute(props: Props, context: ActorContext, ref: RoutedActorRef): CustomRoute

  protected def registerRoutees(context: ActorContext, routees: java.util.List[ActorRef]): Unit = {
    import scala.collection.JavaConverters._
    registerRoutees(context, routees.asScala.toIndexedSeq)
  }

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
    case _                 ⇒ throw new ActorInitializationException("Router actor can only be used in RoutedActorRef")
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
 * Routing configuration that indicates no routing.
 * Oxymoron style.
 */
case object NoRouter extends RouterConfig {
  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route = null
}

/**
 * Router configuration which has no default, i.e. external configuration is required.
 */
case object FromConfig extends RouterConfig {
  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route =
    throw new ConfigurationException("router " + ref + " needs external configuration from file (e.g. application.conf)")
}

/**
 * Java API: Router configuration which has no default, i.e. external configuration is required.
 */
case class FromConfig() extends RouterConfig {
  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route =
    throw new ConfigurationException("router " + ref + " needs external configuration from file (e.g. application.conf)")
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
 * that the round robin should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RoundRobinRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val pool: Option[RouterPool] = None)
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
   */
  def this(t: java.lang.Iterable[String]) = {
    this(routees = iterableAsScalaIterable(t))
  }

  /**
   * Constructor that sets the pool to be used.
   * Java API
   */
  def this(pool: RouterPool) = this(pool = Some(pool))
}

trait RoundRobinLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    val next = new AtomicLong(0)

    def getNext(): ActorRef = {
      val _routees = ref.routees
      _routees((next.getAndIncrement % _routees.size).asInstanceOf[Int])
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, ref.routees)
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
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RandomRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val pool: Option[RouterPool] = None)
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
   */
  def this(t: java.lang.Iterable[String]) = {
    this(routees = iterableAsScalaIterable(t))
  }

  /**
   * Constructor that sets the pool to be used.
   * Java API
   */
  def this(pool: RouterPool) = this(pool = Some(pool))
}

trait RandomLike { this: RouterConfig ⇒

  import java.security.SecureRandom

  def nrOfInstances: Int

  def routees: Iterable[String]

  private val random = new ThreadLocal[SecureRandom] {
    override def initialValue = SecureRandom.getInstance("SHA1PRNG")
  }

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    def getNext(): ActorRef = {
      ref.routees(random.get.nextInt(ref.routees.size))
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, ref.routees)
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
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class BroadcastRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val pool: Option[RouterPool] = None)
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
   */
  def this(t: java.lang.Iterable[String]) = {
    this(routees = iterableAsScalaIterable(t))
  }

  /**
   * Constructor that sets the pool to be used.
   * Java API
   */
  def this(pool: RouterPool) = this(pool = Some(pool))

}

trait BroadcastLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    {
      case (sender, message) ⇒
        message match {
          case _ ⇒ toAll(sender, ref.routees)
        }
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
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class ScatterGatherFirstCompletedRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, within: Duration,
                                             override val pool: Option[RouterPool] = None)
  extends RouterConfig with ScatterGatherFirstCompletedLike {

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
   */
  def this(t: java.lang.Iterable[String], w: Duration) = {
    this(routees = iterableAsScalaIterable(t), within = w)
  }

  /**
   * Constructor that sets the pool to be used.
   * Java API
   */
  def this(pool: RouterPool, w: Duration) = this(pool = Some(pool), within = w)
}

trait ScatterGatherFirstCompletedLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def within: Duration

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    {
      case (sender, message) ⇒
        val asker = context.asInstanceOf[ActorCell].systemImpl.provider.ask(Timeout(within)).get
        asker.result.pipeTo(sender)
        message match {
          case _ ⇒ toAll(asker, ref.routees)
        }
    }
  }
}

/**
 * Routers with dynamically resizable number of routees is implemented by providing a pool
 * implementation in [[akka.routing.RouterConfig]]. When the resize method is invoked you can
 * create and register more routees with `routerConfig.registerRoutees(actorContext, newRoutees)
 * or remove routees with `routerConfig.unregisterRoutees(actorContext, abandonedRoutees)` and
 * sending [[akka.actor.PoisonPill]] to them.
 */
trait RouterPool {
  def resize(props: Props, actorContext: ActorContext, currentRoutees: IndexedSeq[ActorRef], routerConfig: RouterConfig)
}

case class DefaultRouterPool(
  /**
   * The fewest number of routees the pool should ever have
   */
  lowerBound: Int = 1,
  /**
 * The most number of routees the pool should ever have
 */
  upperBound: Int = 10,
  /**
 * A routee is considered to be busy (under pressure) when
 * it has at least this number of messages in its mailbox.
 * When pressureThreshold is defined as 0 the routee
 * is considered busy when it is currently processing a
 * message.
 */
  pressureThreshold: Int = 3,
  /**
 * Percentage to increase capacity whenever all routees are busy.
 * For example, 0.2 would increase 20%, etc.
 */
  rampupRate: Double = 0.2,
  /**
 * Fraction of capacity the pool has to fall below before backing off.
 * For example, if this is 0.7, then we'll remove some routees when
 * less than 70% of routees are busy.
 * Use 0.0 to avoid removal of routees.
 */
  backoffThreshold: Double = 0.7,
  /**
 * Fraction of routees to be removed when the pool reaches the
 * backoffThreshold.
 * Use 0.0 to avoid removal of routees.
 */
  backoffRate: Double = 0.1,
  /**
 * When the pool shrink the abandoned actors are stopped with PoisonPill after this delay
 */
  stopDelay: Duration = 1.second) extends RouterPool {

  def resize(props: Props, actorContext: ActorContext, currentRoutees: IndexedSeq[ActorRef], routerConfig: RouterConfig) {
    val requestedCapacity = capacity(currentRoutees)

    if (requestedCapacity > 0) {
      val newRoutees = routerConfig.createRoutees(props, actorContext, requestedCapacity, Nil)
      routerConfig.registerRoutees(actorContext, newRoutees)
    } else if (requestedCapacity < 0) {
      val (keep, abandon) = currentRoutees.splitAt(currentRoutees.length + requestedCapacity)
      routerConfig.unregisterRoutees(actorContext, abandon)
      delayedStop(actorContext.system.scheduler, abandon)
    }
  }

  /**
   * Give concurrent messages a chance to be placed in mailbox before
   * sending PoisonPill.
   */
  protected def delayedStop(scheduler: Scheduler, abandon: IndexedSeq[ActorRef]) {
    scheduler.scheduleOnce(stopDelay) {
      abandon foreach (_ ! PoisonPill)
    }
  }

  /**
   * Returns the overall desired change in pool capacity. Positive value will
   * add routees to the pool. Negative value will remove routees from the
   * pool.
   * @param routees The current actor in the pool
   * @return the number of routees by which the pool should be adjusted (positive, negative or zero)
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
   * Default implementation:
   * When `pressureThreshold` > 0 the number of routees with at least
   * the configured `pressureThreshold` messages in their mailbox,
   * otherwise number of routees currently processing a
   * message.
   *
   * @param routees the current pool of routees
   * @return number of busy routees, between 0 and routees.size
   */
  def pressure(routees: Seq[ActorRef]): Int = {
    if (pressureThreshold > 0) {
      routees count {
        case a: LocalActorRef ⇒ a.underlying.mailbox.numberOfMessages >= pressureThreshold
        case _                ⇒ false
      }
    } else {
      routees count {
        case a: LocalActorRef ⇒
          val cell = a.underlying
          cell.mailbox.isScheduled && cell.currentMessage != null
        case _ ⇒ false
      }
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
      math.ceil(-1.0 * backoffRate * capacity) toInt
    else 0

}

