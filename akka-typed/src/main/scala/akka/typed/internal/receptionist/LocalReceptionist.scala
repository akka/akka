package akka.typed.internal.receptionist

import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.Terminated
import akka.typed.receptionist.Receptionist._
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.Actor.immutable
import akka.typed.scaladsl.Actor.same
import akka.typed.scaladsl.ActorContext
import akka.util.TypedMultiMap

private[typed] object LocalReceptionist {
  /**
   * Initial behavior of a receptionist, used to create a new receptionist like in the following:
   *
   * {{{
   * val receptionist: ActorRef[Receptionist.Command] = ctx.spawn(Props(Receptionist.behavior), "receptionist")
   * }}}
   */
  val behavior: Behavior[Command] = behavior(TypedMultiMap.empty[AbstractServiceKey, KV], Subscriptions.empty).narrow[Command]

  private type KV[K <: AbstractServiceKey] = ActorRef[K#Protocol]
  private type ServiceMap = TypedMultiMap[AbstractServiceKey, KV]

  private[receptionist] sealed abstract class LocalReceptionistInternalCommand extends InternalCommand
  private[receptionist] final case class RegisteredActorTerminated[T](key: ServiceKey[T], address: ActorRef[T]) extends LocalReceptionistInternalCommand
  private[receptionist] final case class SubscriberTerminated[T](key: ServiceKey[T], address: ActorRef[Listing[T]]) extends LocalReceptionistInternalCommand

  private def behavior(
    serviceMap:    ServiceMap,
    subscriptions: Subscriptions): Behavior[AllCommands] = {

    def next(newMap: ServiceMap = serviceMap, newSubs: Subscriptions = subscriptions) =
      behavior(newMap, newSubs)

    def watchWith(ctx: ActorContext[AllCommands], other: ActorRef[_], msg: AllCommands): Unit =
      ctx.spawnAnonymous[Nothing](Actor.deferred[Nothing] { innerCtx ⇒
        innerCtx.watch(other)
        Actor.immutable[Nothing]((_, _) ⇒ Actor.same)
          .onSignal {
            case (_, Terminated(`other`)) ⇒
              ctx.self ! msg
              Actor.stopped
          }
      })

    def updateMap[T](changedKeyHint: ServiceKey[T], f: ServiceMap ⇒ ServiceMap): Behavior[AllCommands] = {
      val newMap = f(serviceMap)
      val newListing = newMap.get(changedKeyHint)
      subscriptions.get(changedKeyHint).foreach(_ ! Listing(changedKeyHint, newListing))
      next(newMap = newMap)
    }

    immutable[AllCommands] { (ctx, msg) ⇒
      msg match {
        case Register(key, address, replyTo) ⇒
          watchWith(ctx, address, RegisteredActorTerminated(key, address))
          replyTo ! Registered(key, address)

          updateMap(key, _.inserted(key)(address))

        case Find(key, replyTo) ⇒
          val set = serviceMap get key
          replyTo ! Listing(key, set)
          same

        case RegisteredActorTerminated(key, address) ⇒
          updateMap(key, _.removed(key)(address))

        case Subscribe(key, subscriber) ⇒
          watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

          next(newSubs = subscriptions.add(key, subscriber))

        case SubscriberTerminated(key, subscriber) ⇒
          next(newSubs = subscriptions.remove(key, subscriber))
      }
    }
  }
}
