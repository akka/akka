/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.util.control.NonFatal
import akka.actor.ActorRef
import akka.actor.SupervisorStrategy
import akka.actor.Props
import akka.dispatch.Dispatchers
import akka.event.Logging
import akka.serialization.SerializationExtension
import java.util.concurrent.atomic.AtomicReference

object ConsistentHashingRouter {
  /**
   * Creates a new ConsistentHashingRouter, routing to the specified routees
   */
  def apply(routees: Iterable[ActorRef]): ConsistentHashingRouter =
    new ConsistentHashingRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): ConsistentHashingRouter = {
    import scala.collection.JavaConverters._
    apply(routees.asScala)
  }

  /**
   * Messages need to implement this interface to define what
   * data to use for the consistent hash key. Note that it's not
   * the hash, but the data to be hashed. If returning an
   * `Array[Byte]` or String it will be used as is, otherwise the
   * configured [[akka.akka.serialization.Serializer]] will
   * be applied to the returned data.
   *
   * If messages can't implement this interface themselves,
   * it's possible to wrap the messages in
   * [[akka.routing.ConsistentHashableEnvelope]]
   */
  trait ConsistentHashable {
    def consistentHashKey: Any
  }

  /**
   * If messages can't implement [[akka.routing.ConsistentHashable]]
   * themselves they can we wrapped by this envelope instead. The
   * router will only send the wrapped message to the destination,
   * i.e. the envelope will be stripped off.
   */
  @SerialVersionUID(1L)
  case class ConsistentHashableEnvelope(message: Any, consistentHashKey: Any)
    extends ConsistentHashable with RouterEnvelope

}
/**
 * A Router that uses consistent hashing to select a connection based on the
 * sent message. The messages must implement [[akka.routing.ConsistentHashable]]
 * or be wrapped in a [[akka.routing.ConsistentHashableEnvelope]] to define what
 * data to use for the consistent hash key.
 *
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical
 * sense as this means that the router should both create new actors and use the 'routees'
 * actor(s). In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
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
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 * @param virtualNodesFactor number of virtual nodes per node, used in [[akka.routing.ConsistantHash]]
 */
@SerialVersionUID(1L)
case class ConsistentHashingRouter(
  nrOfInstances: Int = 0, routees: Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
  val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
  val supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy,
  val virtualNodesFactor: Int = 0)
  extends RouterConfig with ConsistentHashingLike {

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
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = iterableAsScalaIterable(routeePaths))

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String): ConsistentHashingRouter = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): ConsistentHashingRouter = copy(supervisorStrategy = strategy)

  /**
   * Java API for setting the number of virtual nodes per node, used in [[akka.routing.ConsistantHash]]
   */
  def withVirtualNodesFactor(vnodes: Int): ConsistentHashingRouter = copy(virtualNodesFactor = vnodes)

  /**
   * Uses the resizer of the given RouterConfig if this RouterConfig
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
trait ConsistentHashingLike { this: RouterConfig ⇒

  import ConsistentHashingRouter._

  def nrOfInstances: Int

  def routees: Iterable[String]

  def virtualNodesFactor: Int

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    val log = Logging(routeeProvider.context.system, routeeProvider.context.self)
    val vnodes =
      if (virtualNodesFactor == 0) routeeProvider.context.system.settings.DefaultVirtualNodesFactor
      else virtualNodesFactor

    // tuple of routees and the ConsistentHash, updated together in updateConsistentHash
    val consistentHashRef = new AtomicReference[(IndexedSeq[ActorRef], ConsistentHash[ActorRef])]((null, null))
    updateConsistentHash()

    // update consistentHash when routees has changed
    // changes to routees are rare and when no changes this is a quick operation
    def updateConsistentHash(): ConsistentHash[ActorRef] = {
      val oldConsistentHashTuple = consistentHashRef.get
      val (oldConsistentHashRoutees, oldConsistentHash) = oldConsistentHashTuple
      val currentRoutees = routeeProvider.routees
      if (currentRoutees ne oldConsistentHashRoutees) {
        // when other instance, same content, no need to re-hash, but try to set routees
        val consistentHash =
          if (currentRoutees == oldConsistentHashRoutees) oldConsistentHash
          else ConsistentHash(currentRoutees, vnodes) // re-hash
        // ignore, don't update, in case of CAS failure
        consistentHashRef.compareAndSet(oldConsistentHashTuple, (currentRoutees, consistentHash))
        consistentHash
      } else oldConsistentHash
    }

    def target(hashData: Any): ActorRef = try {
      val hash = hashData match {
        case bytes: Array[Byte] ⇒ bytes
        case str: String        ⇒ str.getBytes("UTF-8")
        case x: AnyRef          ⇒ SerializationExtension(routeeProvider.context.system).serialize(x).get
      }
      val currentConsistenHash = updateConsistentHash()
      if (currentConsistenHash.isEmpty) routeeProvider.context.system.deadLetters
      else currentConsistenHash.nodeFor(hash)
    } catch {
      case NonFatal(e) ⇒
        // serialization failed
        log.warning("Couldn't route message with consistentHashKey [{}] due to [{}]", hashData, e.getMessage)
        routeeProvider.context.system.deadLetters
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg)               ⇒ toAll(sender, routeeProvider.routees)
          case hashable: ConsistentHashable ⇒ List(Destination(sender, target(hashable.consistentHashKey)))
          case other ⇒
            log.warning("Message [{}] must implement [{}] or be wrapped in [{}]",
              message.getClass.getName, classOf[ConsistentHashable].getName,
              classOf[ConsistentHashableEnvelope].getName)
            List(Destination(sender, routeeProvider.context.system.deadLetters))
        }
    }
  }
}