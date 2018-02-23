/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.receptionist

import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.receptionist.Receptionist._
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors.immutable
import akka.actor.typed.scaladsl.Behaviors.same
import akka.actor.typed.scaladsl.ActorContext
import akka.util.TypedMultiMap

/**
 * Marker interface to use with dynamic access
 *
 * Internal API
 */
@InternalApi
private[akka] trait ReceptionistBehaviorProvider {
  def behavior: Behavior[Command]
}

/** Internal API */
@InternalApi
private[akka] object ReceptionistImpl extends ReceptionistBehaviorProvider {
  // FIXME: make sure to provide serializer
  final case class DefaultServiceKey[T](id: String, typeName: String) extends ServiceKey[T] {
    override def toString: String = s"ServiceKey[$typeName]($id)"
  }

  /**
   * Interface to allow plugging of external service discovery infrastructure in to the existing receptionist API.
   */
  trait ExternalInterface[State] {
    def onRegister[T](key: ServiceKey[T], ref: ActorRef[T]): Unit
    def onUnregister[T](key: ServiceKey[T], ref: ActorRef[T]): Unit
    def onExternalUpdate(update: State)

    final case class RegistrationsChangedExternally(changes: Map[AbstractServiceKey, Set[ActorRef[_]]], state: State) extends ReceptionistInternalCommand
  }

  object LocalExternalInterface extends ExternalInterface[LocalServiceRegistry] {
    def onRegister[T](key: ServiceKey[T], ref: ActorRef[T]): Unit = ()
    def onUnregister[T](key: ServiceKey[T], ref: ActorRef[T]): Unit = ()
    def onExternalUpdate(update: LocalServiceRegistry): Unit = ()
  }

  override def behavior: Behavior[Command] = localOnlyBehavior
  val localOnlyBehavior: Behavior[Command] = init(_ ⇒ LocalExternalInterface)

  type KV[K <: AbstractServiceKey] = ActorRef[K#Protocol]
  type LocalServiceRegistry = TypedMultiMap[AbstractServiceKey, KV]
  object LocalServiceRegistry {
    val empty: LocalServiceRegistry = TypedMultiMap.empty[AbstractServiceKey, KV]
  }

  sealed abstract class ReceptionistInternalCommand extends InternalCommand
  final case class RegisteredActorTerminated[T](key: ServiceKey[T], ref: ActorRef[T]) extends ReceptionistInternalCommand
  final case class SubscriberTerminated[T](key: ServiceKey[T], ref: ActorRef[MessageImpls.Listing[T]]) extends ReceptionistInternalCommand
  object NodesRemoved {
    val empty = NodesRemoved(Set.empty)
  }
  final case class NodesRemoved(addresses: Set[Address]) extends ReceptionistInternalCommand

  type SubscriptionsKV[K <: AbstractServiceKey] = ActorRef[MessageImpls.Listing[K#Protocol]]
  type SubscriptionRegistry = TypedMultiMap[AbstractServiceKey, SubscriptionsKV]

  private[akka] def init[State](externalInterfaceFactory: ActorContext[AllCommands] ⇒ ExternalInterface[State]): Behavior[Command] =
    Behaviors.setup[AllCommands] { ctx ⇒
      val externalInterface = externalInterfaceFactory(ctx)
      behavior(
        TypedMultiMap.empty[AbstractServiceKey, KV],
        TypedMultiMap.empty[AbstractServiceKey, SubscriptionsKV],
        externalInterface)
    }.narrow[Command]

  private def behavior[State](
    serviceRegistry:   LocalServiceRegistry,
    subscriptions:     SubscriptionRegistry,
    externalInterface: ExternalInterface[State]): Behavior[AllCommands] = {

    // Helper to create new state
    def next(newRegistry: LocalServiceRegistry = serviceRegistry, newSubscriptions: SubscriptionRegistry = subscriptions) =
      behavior(newRegistry, newSubscriptions, externalInterface)

    /*
     * Hack to allow multiple termination notifications per target
     * FIXME: replace by simple map in our state
     */
    def watchWith(ctx: ActorContext[AllCommands], target: ActorRef[_], msg: AllCommands): Unit =
      ctx.spawnAnonymous[Nothing](Behaviors.setup[Nothing] { innerCtx ⇒
        innerCtx.watch(target)
        Behaviors.immutable[Nothing]((_, _) ⇒ Behaviors.same)
          .onSignal {
            case (_, Terminated(`target`)) ⇒
              ctx.self ! msg
              Behaviors.stopped
          }
      })

    // Helper that makes sure that subscribers are notified when an entry is changed
    def updateRegistry(changedKeysHint: Set[AbstractServiceKey], f: LocalServiceRegistry ⇒ LocalServiceRegistry): Behavior[AllCommands] = {
      val newRegistry = f(serviceRegistry)

      def notifySubscribersFor[T](key: AbstractServiceKey): Unit = {
        val newListing = newRegistry.get(key)
        subscriptions.get(key).foreach(_ ! MessageImpls.Listing(key.asServiceKey, newListing))
      }

      changedKeysHint foreach notifySubscribersFor
      next(newRegistry = newRegistry)
    }

    def replyWithListing[T](key: ServiceKey[T], replyTo: ActorRef[Listing]): Unit =
      replyTo ! MessageImpls.Listing(key, serviceRegistry get key)

    immutable[AllCommands] { (ctx, msg) ⇒
      msg match {
        case MessageImpls.Register(key, serviceInstance, maybeReplyTo) ⇒
          ctx.log.debug("Actor was registered: {} {}", key, serviceInstance)
          watchWith(ctx, serviceInstance, RegisteredActorTerminated(key, serviceInstance))
          maybeReplyTo match {
            case Some(replyTo) ⇒ replyTo ! MessageImpls.Registered(key, serviceInstance)
            case None          ⇒
          }
          externalInterface.onRegister(key, serviceInstance)

          updateRegistry(Set(key), _.inserted(key)(serviceInstance))

        case MessageImpls.Find(key, replyTo) ⇒
          replyWithListing(key, replyTo)

          same

        case externalInterface.RegistrationsChangedExternally(changes, state) ⇒

          ctx.log.debug("Registration changed: {}", changes)

          // FIXME: get rid of casts
          def makeChanges(registry: LocalServiceRegistry): LocalServiceRegistry =
            changes.foldLeft(registry) {
              case (reg, (key, values)) ⇒
                reg.setAll(key)(values.asInstanceOf[Set[ActorRef[key.Protocol]]])
            }
          externalInterface.onExternalUpdate(state)
          updateRegistry(changes.keySet, makeChanges) // overwrite all changed keys

        case RegisteredActorTerminated(key, serviceInstance) ⇒
          ctx.log.debug("Registered actor terminated: {} {}", key, serviceInstance)
          externalInterface.onUnregister(key, serviceInstance)
          updateRegistry(Set(key), _.removed(key)(serviceInstance))

        case NodesRemoved(addresses) ⇒
          if (addresses.isEmpty)
            Behaviors.same
          else {
            import akka.actor.typed.scaladsl.adapter._
            val localAddress = ctx.system.toUntyped.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

            def isOnRemovedNode(ref: ActorRef[_]): Boolean = {
              if (ref.path.address.hasLocalScope) addresses(localAddress)
              else addresses(ref.path.address)
            }

            var changedKeys = Set.empty[AbstractServiceKey]
            val newRegistry: LocalServiceRegistry = {
              serviceRegistry.keySet.foldLeft(serviceRegistry) {
                case (reg, key) ⇒
                  val values = reg.get(key)
                  val newValues = values.filterNot(isOnRemovedNode)
                  if (values.size == newValues.size) reg // no change
                  else {
                    changedKeys += key
                    // FIXME: get rid of casts
                    reg.setAll(key)(newValues.asInstanceOf[Set[ActorRef[key.Protocol]]])
                  }
              }
            }

            if (changedKeys.isEmpty)
              Behaviors.same
            else {
              if (ctx.log.isDebugEnabled)
                ctx.log.debug(
                  "Node(s) [{}] removed, updated keys [{}]",
                  addresses.mkString(","), changedKeys.map(_.asServiceKey.id).mkString(","))
              updateRegistry(changedKeys, _ ⇒ newRegistry)
            }
          }

        case MessageImpls.Subscribe(key, subscriber) ⇒
          watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

          // immediately reply with initial listings to the new subscriber
          replyWithListing(key, subscriber)

          next(newSubscriptions = subscriptions.inserted(key)(subscriber))

        case SubscriberTerminated(key, subscriber) ⇒
          next(newSubscriptions = subscriptions.removed(key)(subscriber))

        case _: InternalCommand ⇒
          // silence compiler exhaustive check
          Behaviors.unhandled
      }
    }
  }
}
