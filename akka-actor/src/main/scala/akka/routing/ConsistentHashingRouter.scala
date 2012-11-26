/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import scala.collection.immutable
import akka.japi.Util.immutableSeq
import scala.util.control.NonFatal
import akka.actor.ActorRef
import akka.actor.SupervisorStrategy
import akka.dispatch.Dispatchers
import akka.event.Logging
import akka.serialization.SerializationExtension
import java.util.concurrent.atomic.AtomicReference
import akka.actor.Address
import akka.actor.ExtendedActorSystem

object ConsistentHashingRouter {
  /**
   * Creates a new ConsistentHashingRouter, routing to the specified routees
   */
  def apply(routees: immutable.Iterable[ActorRef]): ConsistentHashingRouter =
    new ConsistentHashingRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  def create(routees: java.lang.Iterable[ActorRef]): ConsistentHashingRouter = apply(immutableSeq(routees))

  /**
   * If you don't define the `hashMapping` when
   * constructing the [[akka.routing.ConsistentHashingRouter]]
   * the messages need to implement this interface to define what
   * data to use for the consistent hash key. Note that it's not
   * the hash, but the data to be hashed.
   *
   * If returning an `Array[Byte]` or String it will be used as is,
   * otherwise the configured [[akka.akka.serialization.Serializer]]
   * will be applied to the returned data.
   *
   * If messages can't implement this interface themselves,
   * it's possible to wrap the messages in
   * [[akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope]],
   * or use [[akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope]]
   */
  trait ConsistentHashable {
    def consistentHashKey: Any
  }

  /**
   * If you don't define the `hashMapping` when
   * constructing the [[akka.routing.ConsistentHashingRouter]]
   * and messages can't implement [[akka.routing.ConsistentHashingRouter.ConsistentHashable]]
   * themselves they can we wrapped by this envelope instead. The
   * router will only send the wrapped message to the destination,
   * i.e. the envelope will be stripped off.
   */
  @SerialVersionUID(1L)
  final case class ConsistentHashableEnvelope(message: Any, hashKey: Any)
    extends ConsistentHashable with RouterEnvelope {
    override def consistentHashKey: Any = hashKey
  }

  /**
   * Partial function from message to the data to
   * use for the consistent hash key. Note that it's not
   * the hash that is to be returned, but the data to be hashed.
   *
   * If returning an `Array[Byte]` or String it will be used as is,
   * otherwise the configured [[akka.akka.serialization.Serializer]]
   * will be applied to the returned data.
   */
  type ConsistentHashMapping = PartialFunction[Any, Any]

  @SerialVersionUID(1L)
  object emptyConsistentHashMapping extends ConsistentHashMapping {
    def isDefinedAt(x: Any) = false
    def apply(x: Any) = throw new UnsupportedOperationException("Empty ConsistentHashMapping apply()")
  }

  /**
   * JAVA API
   * Mapping from message to the data to use for the consistent hash key.
   * Note that it's not the hash that is to be returned, but the data to be
   * hashed.
   *
   * May return `null` to indicate that the message is not handled by
   * this mapping.
   *
   * If returning an `Array[Byte]` or String it will be used as is,
   * otherwise the configured [[akka.akka.serialization.Serializer]]
   * will be applied to the returned data.
   */
  trait ConsistentHashMapper {
    def hashKey(message: Any): Any
  }
}
/**
 * A Router that uses consistent hashing to select a connection based on the
 * sent message.
 *
 * There is 3 ways to define what data to use for the consistent hash key.
 *
 * 1. You can define `hashMapping` / `withHashMapper`
 * of the router to map incoming messages to their consistent hash key.
 * This makes the decision transparent for the sender.
 *
 * 2. The messages may implement [[akka.routing.ConsistentHashingRouter.ConsistentHashable]].
 * The key is part of the message and it's convenient to define it together
 * with the message definition.
 *
 * 3. The messages can be be wrapped in a [[akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope]]
 * to define what data to use for the consistent hash key. The sender knows
 * the key to use.
 *
 * These ways to define the consistent hash key can be use together and at
 * the same time for one router. The `hashMapping` is tried first.
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
 * @param hashMapping partial function from message to the data to
 *   use for the consistent hash key
 */
@SerialVersionUID(1L)
case class ConsistentHashingRouter(
  nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
  val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
  val supervisorStrategy: SupervisorStrategy = Router.defaultSupervisorStrategy,
  val virtualNodesFactor: Int = 0,
  val hashMapping: ConsistentHashingRouter.ConsistentHashMapping = ConsistentHashingRouter.emptyConsistentHashMapping)
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
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = immutableSeq(routeePaths))

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
   * Java API for setting the mapping from message to the data to use for the consistent hash key.
   */
  def withHashMapper(mapping: ConsistentHashingRouter.ConsistentHashMapper) = {
    copy(hashMapping = {
      case message if (mapping.hashKey(message).asInstanceOf[AnyRef] ne null) ⇒
        mapping.hashKey(message)
    })
  }

  /**
   * Uses the resizer of the given RouterConfig if this RouterConfig
   * doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   * Uses the the `hashMapping` defined in code, since
   * that can't be defined in configuration.
   */
  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case _: FromConfig | _: NoRouter ⇒ this
    case otherRouter: ConsistentHashingRouter ⇒
      val useResizer =
        if (this.resizer.isEmpty && otherRouter.resizer.isDefined) otherRouter.resizer
        else this.resizer
      copy(resizer = useResizer, hashMapping = otherRouter.hashMapping)
    case _ ⇒ throw new IllegalArgumentException("Expected ConsistentHashingRouter, got [%s]".format(other))
  }
}

/**
 * The core pieces of the routing logic is located in this
 * trait to be able to extend.
 */
trait ConsistentHashingLike { this: RouterConfig ⇒

  import ConsistentHashingRouter._

  def nrOfInstances: Int

  def routees: immutable.Iterable[String]

  def virtualNodesFactor: Int

  def hashMapping: ConsistentHashMapping

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    val log = Logging(routeeProvider.context.system, routeeProvider.context.self)
    val selfAddress = routeeProvider.context.system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
    val vnodes =
      if (virtualNodesFactor == 0) routeeProvider.context.system.settings.DefaultVirtualNodesFactor
      else virtualNodesFactor

    // tuple of routees and the ConsistentHash, updated together in updateConsistentHash
    val consistentHashRef = new AtomicReference[(IndexedSeq[ConsistentActorRef], ConsistentHash[ConsistentActorRef])]((null, null))
    updateConsistentHash()

    // update consistentHash when routees has changed
    // changes to routees are rare and when no changes this is a quick operation
    def updateConsistentHash(): ConsistentHash[ConsistentActorRef] = {
      val oldConsistentHashTuple = consistentHashRef.get
      val (oldConsistentHashRoutees, oldConsistentHash) = oldConsistentHashTuple
      val currentRoutees = routeeProvider.routees map { ConsistentActorRef(_, selfAddress) }

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
      val currentConsistenHash = updateConsistentHash()
      if (currentConsistenHash.isEmpty) routeeProvider.context.system.deadLetters
      else hashData match {
        case bytes: Array[Byte] ⇒ currentConsistenHash.nodeFor(bytes).actorRef
        case str: String        ⇒ currentConsistenHash.nodeFor(str).actorRef
        case x: AnyRef          ⇒ currentConsistenHash.nodeFor(SerializationExtension(routeeProvider.context.system).serialize(x).get).actorRef
      }
    } catch {
      case NonFatal(e) ⇒
        // serialization failed
        log.warning("Couldn't route message with consistent hash key [{}] due to [{}]", hashData, e.getMessage)
        routeeProvider.context.system.deadLetters
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routeeProvider.routees)
          case _ if hashMapping.isDefinedAt(message) ⇒
            List(Destination(sender, target(hashMapping(message))))
          case hashable: ConsistentHashable ⇒ List(Destination(sender, target(hashable.consistentHashKey)))
          case other ⇒
            log.warning("Message [{}] must be handled by hashMapping, or implement [{}] or be wrapped in [{}]",
              message.getClass.getName, classOf[ConsistentHashable].getName,
              classOf[ConsistentHashableEnvelope].getName)
            List(Destination(sender, routeeProvider.context.system.deadLetters))
        }

    }
  }
}

/**
 * INTERNAL API
 * Important to use ActorRef with full address, with host and port, in the hash ring,
 * so that same ring is produced on different nodes.
 * The ConsistentHash uses toString of the ring nodes, and the ActorRef itself
 * isn't a good representation, because LocalActorRef doesn't include the
 * host and port.
 */
private[akka] case class ConsistentActorRef(actorRef: ActorRef, selfAddress: Address) {
  override def toString: String = {
    actorRef.path.address match {
      case Address(_, _, None, None) ⇒ actorRef.path.toStringWithAddress(selfAddress)
      case a                         ⇒ actorRef.path.toString
    }
  }
}