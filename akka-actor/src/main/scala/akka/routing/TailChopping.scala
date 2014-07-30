/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.immutable
import akka.actor._
import akka.dispatch.Dispatchers
import com.typesafe.config.Config
import akka.japi.Util.immutableSeq
import scala.concurrent.{ ExecutionContext, Promise }
import akka.pattern.{ AskTimeoutException, after, ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout
import akka.util.Helpers.ConfigOps

import scala.util.Random

/**
 * Sends the message to a first, random picked, routee,
 * then wait a specified `interval` and then send to a second, random picked, and so on till one full cycle.
 *
 * @param scheduler schedules sending messages to routees
 *
 * @param within expecting at least one reply within this duration, otherwise
 *   it will reply with [[akka.pattern.AskTimeoutException]] in a [[akka.actor.Status.Failure]]
 *
 * @param interval duration after which next routee will be picked
 *
 * @param context execution context used by scheduler
 */
@SerialVersionUID(1L)
final case class TailChoppingRoutingLogic(scheduler: Scheduler, within: FiniteDuration,
                                          interval: FiniteDuration, context: ExecutionContext) extends RoutingLogic {
  override def select(message: Any, routees: immutable.IndexedSeq[Routee]): Routee = {
    if (routees.isEmpty) NoRoutee
    else TailChoppingRoutees(scheduler, routees, within, interval)(context)
  }
}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[akka] final case class TailChoppingRoutees(
  scheduler: Scheduler, routees: immutable.IndexedSeq[Routee],
  within: FiniteDuration, interval: FiniteDuration)(implicit ec: ExecutionContext) extends Routee {

  override def send(message: Any, sender: ActorRef): Unit = {
    implicit val timeout = Timeout(within)
    val promise = Promise[Any]()
    val shuffled = Random.shuffle(routees)

    val aIdx = new AtomicInteger()
    val size = shuffled.length

    val tryWithNext = scheduler.schedule(0.millis, interval) {
      val idx = aIdx.getAndIncrement
      if (idx < size) {
        shuffled(idx) match {
          case ActorRefRoutee(ref) ⇒
            promise.tryCompleteWith(ref.ask(message))
          case ActorSelectionRoutee(sel) ⇒
            promise.tryCompleteWith(sel.ask(message))
          case _ ⇒
        }
      }
    }

    val sendTimeout = scheduler.scheduleOnce(within)(promise.tryFailure(
      new AskTimeoutException(s"Ask timed out on [$sender] after [$within.toMillis} ms]")))

    val f = promise.future
    f.onComplete {
      case _ ⇒
        tryWithNext.cancel()
        sendTimeout.cancel()
    }
    f.pipeTo(sender)
  }
}

/**
 * A router poll thats sends the message to a first, random picked, routee,
 * then wait a specified `interval` and then send to a second, random picked, and so on till one full cycle..
 *
 * The configuration parameter trumps the constructor arguments. This means that
 * if you provide `nrOfInstances` during instantiation they will be ignored if
 * the router is defined in the configuration file for the actor being used.
 *
 * <h1>Supervision Setup</h1>
 *
 * Any routees that are created by a router will be created as the router's children.
 * The router is therefore also the children's supervisor.
 *
 * The supervision strategy of the router actor can be configured with
 * [[#withSupervisorStrategy]]. If no strategy is provided, routers default to
 * a strategy of “always escalate”. This means that errors are passed up to the
 * router's supervisor for handling.
 *
 * The router's supervisor will treat the error as an error with the router itself.
 * Therefore a directive to stop or restart will cause the router itself to stop or
 * restart. The router, in turn, will cause its children to stop and restart.
 *
 * @param nrOfInstances initial number of routees in the pool
 *
 * @param resizer optional resizer that dynamically adjust the pool size
 *
 * @param within expecting at least one reply within this duration, otherwise
 *   it will reply with [[akka.pattern.AskTimeoutException]] in a [[akka.actor.Status.Failure]]
 *
 * @param interval duration after which next routee will be picked
 *
 * @param supervisorStrategy strategy for supervising the routees, see 'Supervision Setup'
 *
 * @param routerDispatcher dispatcher to use for the router head actor, which handles
 *   supervision, death watch and router management messages
 */
@SerialVersionUID(1L)
final case class TailChoppingPool(
  override val nrOfInstances: Int, override val resizer: Option[Resizer] = None,
  within: FiniteDuration,
  interval: FiniteDuration,
  override val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy,
  override val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
  override val usePoolDispatcher: Boolean = false)
  extends Pool with PoolOverrideUnsetConfig[TailChoppingPool] {

  def this(config: Config) =
    this(
      nrOfInstances = config.getInt("nr-of-instances"),
      within = config.getMillisDuration("within"),
      interval = config.getMillisDuration("tail-chopping-router.interval"),
      resizer = DefaultResizer.fromConfig(config),
      usePoolDispatcher = config.hasPath("pool-dispatcher"))

  /**
   * Java API
   * @param nr initial number of routees in the pool
   * @param within expecting at least one reply within this duration, otherwise
   *   it will reply with [[akka.pattern.AskTimeoutException]] in a [[akka.actor.Status.Failure]]
   * @param interval duration after which next routee will be picked
   */
  def this(nr: Int, within: FiniteDuration, interval: FiniteDuration) =
    this(nrOfInstances = nr, within = within, interval = interval)

  override def createRouter(system: ActorSystem): Router =
    new Router(TailChoppingRoutingLogic(system.scheduler, within,
      interval, system.dispatchers.lookup(routerDispatcher)))

  /**
   * Setting the supervisor strategy to be used for the “head” Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): TailChoppingPool = copy(supervisorStrategy = strategy)

  /**
   * Setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): TailChoppingPool = copy(resizer = Some(resizer))

  /**
   * Setting the dispatcher to be used for the router head actor,  which handles
   * supervision, death watch and router management messages.
   */
  def withDispatcher(dispatcherId: String): TailChoppingPool = copy(routerDispatcher = dispatcherId)

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = this.overrideUnsetConfig(other)

}

/**
 * A router group that sends the message to a first, random picked, routee,
 * then wait a specified `interval` and then send to a second, random picked, and so on till one full cycle..
 *
 * The configuration parameter trumps the constructor arguments. This means that
 * if you provide `paths` during instantiation they will be ignored if
 * the router is defined in the configuration file for the actor being used.
 *
 * @param paths string representation of the actor paths of the routees, messages are
 *   sent with [[akka.actor.ActorSelection]] to these paths
 *
 * @param within expecting at least one reply within this duration, otherwise
 *   it will reply with [[akka.pattern.AskTimeoutException]] in a [[akka.actor.Status.Failure]]
 *
 * @param interval duration after which next routee will be picked
 *
 * @param routerDispatcher dispatcher to use for the router head actor, which handles
 *   router management messages
 */
final case class TailChoppingGroup(
  override val paths: immutable.Iterable[String],
  within: FiniteDuration,
  interval: FiniteDuration,
  override val routerDispatcher: String = Dispatchers.DefaultDispatcherId) extends Group {

  def this(config: Config) =
    this(
      paths = immutableSeq(config.getStringList("routees.paths")),
      within = config.getMillisDuration("within"),
      interval = config.getMillisDuration("tail-chopping-router.interval"))

  /**
   * Java API
   * @param routeePaths string representation of the actor paths of the routees, messages are
   *   sent with [[akka.actor.ActorSelection]] to these paths
   * @param within expecting at least one reply within this duration, otherwise
   *   it will reply with [[akka.pattern.AskTimeoutException]] in a [[akka.actor.Status.Failure]]
   * @param interval duration after which next routee will be picked
   */
  def this(routeePaths: java.lang.Iterable[String], within: FiniteDuration, interval: FiniteDuration) =
    this(paths = immutableSeq(routeePaths), within = within, interval = interval)

  override def createRouter(system: ActorSystem): Router =
    new Router(TailChoppingRoutingLogic(system.scheduler, within, interval, system.dispatchers.lookup(routerDispatcher)))

  /**
   * Setting the dispatcher to be used for the router head actor, which handles
   * router management messages
   */
  def withDispatcher(dispatcherId: String): TailChoppingGroup = copy(routerDispatcher = dispatcherId)

}
