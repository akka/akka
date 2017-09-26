/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.internal.receptionist

import akka.annotation.InternalApi
import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.Terminated
import akka.typed.receptionist.Receptionist._
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.Actor.immutable
import akka.typed.scaladsl.Actor.same
import akka.typed.scaladsl.ActorContext
import akka.util.TypedMultiMap

import scala.reflect.ClassTag

/**
 * Marker interface to use with dynamic access
 *
 * Internal API
 */
@InternalApi
private[typed] trait ReceptionistBehaviorProvider {
  def behavior: Behavior[Command]
}

/** Internal API */
@InternalApi
private[typed] object ReceptionistImpl extends ReceptionistBehaviorProvider {
  // FIXME: make sure to provide serializer
  final case class DefaultServiceKey[T](id: String, typeName: String) extends ServiceKey[T] {
    override def toString: String = s"ServiceKey[$typeName]($id)"
  }

  /**
   * Interface to allow plugging of external service discovery infrastructure in to the existing receptionist API.
   */
  trait ExternalInterface {
    def onRegister[T](key: ServiceKey[T], address: ActorRef[T]): Unit
    def onUnregister[T](key: ServiceKey[T], address: ActorRef[T]): Unit
  }
  object LocalExternalInterface extends ExternalInterface {
    def onRegister[T](key: ServiceKey[T], address: ActorRef[T]): Unit = ()
    def onUnregister[T](key: ServiceKey[T], address: ActorRef[T]): Unit = ()
  }

  override def behavior: Behavior[Command] = localOnlyBehavior
  val localOnlyBehavior: Behavior[Command] = init(_ ⇒ LocalExternalInterface)

  type KV[K <: AbstractServiceKey] = ActorRef[K#Protocol]
  type LocalServiceRegistry = TypedMultiMap[AbstractServiceKey, KV]
  object LocalServiceRegistry {
    val empty: LocalServiceRegistry = TypedMultiMap.empty[AbstractServiceKey, KV]
  }

  sealed abstract class ReceptionistInternalCommand extends InternalCommand
  final case class RegisteredActorTerminated[T](key: ServiceKey[T], address: ActorRef[T]) extends ReceptionistInternalCommand
  final case class SubscriberTerminated[T](key: ServiceKey[T], address: ActorRef[Listing[T]]) extends ReceptionistInternalCommand
  final case class RegistrationsChangedExternally(changes: Map[AbstractServiceKey, Set[ActorRef[_]]]) extends ReceptionistInternalCommand

  type SubscriptionsKV[K <: AbstractServiceKey] = ActorRef[Listing[K#Protocol]]
  type SubscriptionRegistry = TypedMultiMap[AbstractServiceKey, SubscriptionsKV]

  private[typed] def init(externalInterfaceFactory: ActorContext[AllCommands] ⇒ ExternalInterface): Behavior[Command] =
    Actor.deferred[AllCommands] { ctx ⇒
      val externalInterface = externalInterfaceFactory(ctx)
      behavior(
        TypedMultiMap.empty[AbstractServiceKey, KV],
        TypedMultiMap.empty[AbstractServiceKey, SubscriptionsKV],
        externalInterface)
    }.narrow[Command]

  private def behavior(
    serviceRegistry:   LocalServiceRegistry,
    subscriptions:     SubscriptionRegistry,
    externalInterface: ExternalInterface): Behavior[AllCommands] = {

    // Helper to create new state
    def next(newRegistry: LocalServiceRegistry = serviceRegistry, newSubscriptions: SubscriptionRegistry = subscriptions) =
      behavior(newRegistry, newSubscriptions, externalInterface)

    /*
     * Hack to allow multiple termination notifications per target
     * FIXME: replace by simple map in our state
     */
    def watchWith(ctx: ActorContext[AllCommands], target: ActorRef[_], msg: AllCommands): Unit =
      ctx.spawnAnonymous[Nothing](Actor.deferred[Nothing] { innerCtx ⇒
        innerCtx.watch(target)
        Actor.immutable[Nothing]((_, _) ⇒ Actor.same)
          .onSignal {
            case (_, Terminated(`target`)) ⇒
              ctx.self ! msg
              Actor.stopped
          }
      })

    // Helper that makes sure that subscribers are notified when an entry is changed
    def updateRegistry(changedKeysHint: Set[AbstractServiceKey], f: LocalServiceRegistry ⇒ LocalServiceRegistry): Behavior[AllCommands] = {
      val newRegistry = f(serviceRegistry)

      def notifySubscribersFor[T](key: AbstractServiceKey): Unit = {
        val newListing = newRegistry.get(key)
        subscriptions.get(key).foreach(_ ! Listing(key.asServiceKey, newListing))
      }

      changedKeysHint foreach notifySubscribersFor
      next(newRegistry = newRegistry)
    }

    def replyWithListing[T](key: ServiceKey[T], replyTo: ActorRef[Listing[T]]): Unit =
      replyTo ! Listing(key, serviceRegistry get key)

    immutable[AllCommands] { (ctx, msg) ⇒
      msg match {
        case Register(key, serviceInstance, replyTo) ⇒
          ctx.system.log.debug("[{}] Actor was registered: {} {}", ctx.self, key, serviceInstance)
          watchWith(ctx, serviceInstance, RegisteredActorTerminated(key, serviceInstance))
          replyTo ! Registered(key, serviceInstance)
          externalInterface.onRegister(key, serviceInstance)

          updateRegistry(Set(key), _.inserted(key)(serviceInstance))

        case Find(key, replyTo) ⇒
          replyWithListing(key, replyTo)

          same

        case RegistrationsChangedExternally(changes) ⇒

          ctx.system.log.debug("[{}] Registration changed: {}", ctx.self, changes)

          // FIXME: get rid of casts
          def makeChanges(registry: LocalServiceRegistry): LocalServiceRegistry =
            changes.foldLeft(registry) {
              case (reg, (key, values)) ⇒
                reg.setAll(key)(values.asInstanceOf[Set[ActorRef[key.Protocol]]])
            }

          updateRegistry(changes.keySet, makeChanges) // overwrite all changed keys

        case RegisteredActorTerminated(key, serviceInstance) ⇒
          ctx.system.log.debug("[{}] Registered actor terminated: {} {}", ctx.self, key, serviceInstance)
          externalInterface.onUnregister(key, serviceInstance)
          updateRegistry(Set(key), _.removed(key)(serviceInstance))

        case Subscribe(key, subscriber) ⇒
          watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

          // immediately reply with initial listings to the new subscriber
          replyWithListing(key, subscriber)

          next(newSubscriptions = subscriptions.inserted(key)(subscriber))

        case SubscriberTerminated(key, subscriber) ⇒
          next(newSubscriptions = subscriptions.removed(key)(subscriber))

        case _: InternalCommand ⇒
          // silence compiler exhaustive check
          Actor.unhandled
      }
    }
  }
}
