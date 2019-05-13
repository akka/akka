/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.receptionist

import akka.actor.typed.{ ActorRef, Behavior, Terminated }
import akka.actor.typed.receptionist.Receptionist._
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.scaladsl.Behaviors.{ receive, same }
import akka.annotation.InternalApi
import akka.util.TypedMultiMap

/**
 * Marker interface to use with dynamic access
 *
 * INTERNAL API
 */
@InternalApi
private[akka] trait ReceptionistBehaviorProvider {
  def name: String
  def behavior: Behavior[Command]
}

// just to provide a log class
/** INTERNAL API */
@InternalApi
private[akka] final class LocalReceptionist

/** INTERNAL API */
@InternalApi
private[akka] object LocalReceptionist extends ReceptionistBehaviorProvider {

  override val name = "localReceptionist"

  type KV[K <: AbstractServiceKey] = ActorRef[K#Protocol]
  type LocalServiceRegistry = TypedMultiMap[AbstractServiceKey, KV]
  type SubscriptionsKV[K <: AbstractServiceKey] = ActorRef[ReceptionistMessages.Listing[K#Protocol]]
  type SubscriptionRegistry = TypedMultiMap[AbstractServiceKey, SubscriptionsKV]

  sealed trait InternalCommand
  final case class RegisteredActorTerminated[T](key: ServiceKey[T], ref: ActorRef[T]) extends InternalCommand
  final case class SubscriberTerminated[T](key: ServiceKey[T], ref: ActorRef[ReceptionistMessages.Listing[T]])
      extends InternalCommand

  override def behavior: Behavior[Command] = Behaviors.setup { ctx =>
    ctx.setLoggerClass(classOf[LocalReceptionist])
    behavior(TypedMultiMap.empty[AbstractServiceKey, KV], TypedMultiMap.empty[AbstractServiceKey, SubscriptionsKV])
      .narrow[Command]
  }

  private def behavior(serviceRegistry: LocalServiceRegistry, subscriptions: SubscriptionRegistry): Behavior[Any] = {

    // Helper to create new state
    def next(
        newRegistry: LocalServiceRegistry = serviceRegistry,
        newSubscriptions: SubscriptionRegistry = subscriptions) =
      behavior(newRegistry, newSubscriptions)

    /*
     * Hack to allow multiple termination notifications per target
     * FIXME #26505: replace by simple map in our state
     */
    def watchWith(ctx: ActorContext[Any], target: ActorRef[_], msg: InternalCommand): Unit =
      ctx.spawnAnonymous[Nothing](Behaviors.setup[Nothing] { innerCtx =>
        innerCtx.watch(target)
        Behaviors.receiveSignal[Nothing] {
          case (_, Terminated(`target`)) =>
            ctx.self ! msg
            Behaviors.stopped
        }
      })

    // Helper that makes sure that subscribers are notified when an entry is changed
    def updateRegistry(
        changedKeysHint: Set[AbstractServiceKey],
        f: LocalServiceRegistry => LocalServiceRegistry): Behavior[Any] = {
      val newRegistry = f(serviceRegistry)

      def notifySubscribersFor[T](key: AbstractServiceKey): Unit = {
        val newListing = newRegistry.get(key)
        subscriptions.get(key).foreach(_ ! ReceptionistMessages.Listing(key.asServiceKey, newListing))
      }

      changedKeysHint.foreach(notifySubscribersFor)
      next(newRegistry = newRegistry)
    }

    def replyWithListing[T](key: ServiceKey[T], replyTo: ActorRef[Listing]): Unit =
      replyTo ! ReceptionistMessages.Listing(key, serviceRegistry.get(key))

    def onCommand(ctx: ActorContext[Any], cmd: Command): Behavior[Any] = cmd match {
      case ReceptionistMessages.Register(key, serviceInstance, maybeReplyTo) =>
        ctx.log.debug("Actor was registered: {} {}", key, serviceInstance)
        watchWith(ctx, serviceInstance, RegisteredActorTerminated(key, serviceInstance))
        maybeReplyTo match {
          case Some(replyTo) => replyTo ! ReceptionistMessages.Registered(key, serviceInstance)
          case None          =>
        }
        updateRegistry(Set(key), _.inserted(key)(serviceInstance))

      case ReceptionistMessages.Find(key, replyTo) =>
        replyWithListing(key, replyTo)
        same

      case ReceptionistMessages.Subscribe(key, subscriber) =>
        watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

        // immediately reply with initial listings to the new subscriber
        replyWithListing(key, subscriber)

        next(newSubscriptions = subscriptions.inserted(key)(subscriber))
    }

    def onInternal(ctx: ActorContext[Any], cmd: InternalCommand): Behavior[Any] = cmd match {
      case RegisteredActorTerminated(key, serviceInstance) =>
        ctx.log.debug("Registered actor terminated: {} {}", key, serviceInstance)
        updateRegistry(Set(key), _.removed(key)(serviceInstance))

      case SubscriberTerminated(key, subscriber) =>
        next(newSubscriptions = subscriptions.removed(key)(subscriber))
    }

    receive[Any] { (ctx, msg) =>
      msg match {
        case cmd: Command         => onCommand(ctx, cmd)
        case cmd: InternalCommand => onInternal(ctx, cmd)
        case _                    => Behaviors.unhandled
      }
    }
  }
}
