/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import language.implicitConversions
import language.postfixOps
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor._
import akka.ConfigurationException
import akka.dispatch.{ Envelope, Dispatchers }
import akka.pattern.pipe
import akka.japi.Util.immutableSeq
import com.typesafe.config.Config
import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean }
import java.util.concurrent.TimeUnit
import akka.event.Logging.Warning
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.annotation.tailrec
import akka.event.Logging.Warning
import akka.dispatch.{ MailboxType, MessageDispatcher }

/**
 * Sending this message to a router will make it send back its currently used routees.
 * A RouterRoutees message is sent asynchronously to the "requester" containing information
 * about what routees the router is routing over.
 */
@deprecated("Use GetRoutees", "2.3")
@SerialVersionUID(1L) abstract class CurrentRoutees extends RouterManagementMesssage

@deprecated("Use GetRoutees", "2.3")
@SerialVersionUID(1L) case object CurrentRoutees extends CurrentRoutees {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * Message used to carry information about what routees the router is currently using.
 */
@deprecated("Use GetRoutees", "2.3")
@SerialVersionUID(1L)
case class RouterRoutees(routees: immutable.IndexedSeq[ActorRef]) {
  /**
   * Java API
   */
  def getRoutees: java.util.List[ActorRef] = {
    import scala.collection.JavaConverters._
    routees.asJava
  }
}

@deprecated("Use Pool or Group", "2.3")
trait DeprecatedRouterConfig extends Group with Pool

@deprecated("Use RoundRobinPool or RoundRobinGroup", "2.3")
object RoundRobinRouter {
  /**
   * Creates a new RoundRobinRouter, routing to the specified routees
   */
  def apply(routees: immutable.Iterable[ActorRef]): RoundRobinRouter =
    new RoundRobinRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): RoundRobinRouter =
    apply(immutableSeq(routees))
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
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
@deprecated("Use RoundRobinPool or RoundRobinGroup", "2.3")
case class RoundRobinRouter(nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                            val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                            val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy)
  extends DeprecatedRouterConfig with PoolOverrideUnsetConfig[RoundRobinRouter] {

  /**
   * Java API: Constructor that sets nrOfInstances to be created.
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Java API: Constructor that sets the routees to be used.
   *
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = immutableSeq(routeePaths))

  /**
   * Java API: Constructor that sets the resizer to be used.
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  override def paths: immutable.Iterable[String] = routees

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
   * Java API for setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): RoundRobinRouter = copy(resizer = Some(resizer))

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = this.overrideUnsetConfig(other)

  override def createRouter(system: ActorSystem): Router = new Router(RoundRobinRoutingLogic())
}

@deprecated("Use RandomPool or RandomGroup", "2.3")
object RandomRouter {
  /**
   * Creates a new RandomRouter, routing to the specified routees
   */
  def apply(routees: immutable.Iterable[ActorRef]): RandomRouter = new RandomRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): RandomRouter =
    apply(immutableSeq(routees))
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
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
@deprecated("Use RandomPool or RandomGroup", "2.3")
case class RandomRouter(nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                        val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                        val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy)
  extends DeprecatedRouterConfig with PoolOverrideUnsetConfig[RandomRouter] {

  /**
   * Java API: Constructor that sets nrOfInstances to be created.
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Java API: Constructor that sets the routees to be used.
   *
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = immutableSeq(routeePaths))

  /**
   * Java API: Constructor that sets the resizer to be used.
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  override def paths: immutable.Iterable[String] = routees

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
   * Java API for setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): RandomRouter = copy(resizer = Some(resizer))

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = this.overrideUnsetConfig(other)

  override def createRouter(system: ActorSystem): Router = new Router(RandomRoutingLogic())
}

@deprecated("Use SmallestMailboxPool", "2.3")
object SmallestMailboxRouter {
  /**
   * Creates a new SmallestMailboxRouter, routing to the specified routees
   */
  def apply(routees: immutable.Iterable[ActorRef]): SmallestMailboxRouter =
    new SmallestMailboxRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): SmallestMailboxRouter =
    apply(immutableSeq(routees))
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
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
@deprecated("Use SmallestMailboxPool", "2.3")
case class SmallestMailboxRouter(nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                                 val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                 val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy)
  extends DeprecatedRouterConfig with PoolOverrideUnsetConfig[SmallestMailboxRouter] {

  /**
   * Java API: Constructor that sets nrOfInstances to be created.
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Java API: Constructor that sets the routees to be used.
   *
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = immutableSeq(routeePaths))

  /**
   * Java API: Constructor that sets the resizer to be used.
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  override def paths: immutable.Iterable[String] = routees

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
   * Java API for setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): SmallestMailboxRouter = copy(resizer = Some(resizer))

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = this.overrideUnsetConfig(other)

  override def createRouter(system: ActorSystem): Router = new Router(SmallestMailboxRoutingLogic())
}

@deprecated("Use BroadcastPool or BroadcastGroup", "2.3")
object BroadcastRouter {
  /**
   * Creates a new BroadcastRouter, routing to the specified routees
   */
  def apply(routees: immutable.Iterable[ActorRef]): BroadcastRouter = new BroadcastRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): BroadcastRouter =
    apply(immutableSeq(routees))
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
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
@deprecated("Use BroadcastPool or BroadcastGroup", "2.3")
case class BroadcastRouter(nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
                           val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                           val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy)
  extends DeprecatedRouterConfig with PoolOverrideUnsetConfig[BroadcastRouter] {

  /**
   * Java API: Constructor that sets nrOfInstances to be created.
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Java API: Constructor that sets the routees to be used.
   *
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = immutableSeq(routeePaths))

  /**
   * Java API: Constructor that sets the resizer to be used.
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  override def paths: immutable.Iterable[String] = routees

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
   * Java API for setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): BroadcastRouter = copy(resizer = Some(resizer))

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = this.overrideUnsetConfig(other)

  override def createRouter(system: ActorSystem): Router = new Router(BroadcastRoutingLogic())
}

@deprecated("Use ScatterGatherFirstCompletedPool or ScatterGatherFirstCompletedGroup", "2.3")
object ScatterGatherFirstCompletedRouter {
  /**
   * Creates a new ScatterGatherFirstCompletedRouter, routing to the specified routees, timing out after the specified Duration
   */
  def apply(routees: immutable.Iterable[ActorRef], within: FiniteDuration): ScatterGatherFirstCompletedRouter =
    new ScatterGatherFirstCompletedRouter(routees = routees map (_.path.toString), within = within)

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef], within: FiniteDuration): ScatterGatherFirstCompletedRouter =
    apply(immutableSeq(routees), within)
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
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
@deprecated("Use ScatterGatherFirstCompletedPool or ScatterGatherFirstCompletedGroup", "2.3")
case class ScatterGatherFirstCompletedRouter(nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil, within: FiniteDuration,
                                             override val resizer: Option[Resizer] = None,
                                             val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                             val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy)
  extends DeprecatedRouterConfig with PoolOverrideUnsetConfig[ScatterGatherFirstCompletedRouter] {

  if (within <= Duration.Zero) throw new IllegalArgumentException(
    "[within: Duration] can not be zero or negative, was [" + within + "]")

  /**
   * Java API: Constructor that sets nrOfInstances to be created.
   */
  def this(nr: Int, w: FiniteDuration) = this(nrOfInstances = nr, within = w)

  /**
   * Java API: Constructor that sets the routees to be used.
   *
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String], w: FiniteDuration) = this(routees = immutableSeq(routeePaths), within = w)

  /**
   * Java API: Constructor that sets the resizer to be used.
   */
  def this(resizer: Resizer, w: FiniteDuration) = this(resizer = Some(resizer), within = w)

  override def paths: immutable.Iterable[String] = routees

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String) = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): ScatterGatherFirstCompletedRouter = copy(supervisorStrategy = strategy)

  /**
   * Java API for setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): ScatterGatherFirstCompletedRouter = copy(resizer = Some(resizer))

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = this.overrideUnsetConfig(other)

  override def createRouter(system: ActorSystem): Router = new Router(SmallestMailboxRoutingLogic())
}

