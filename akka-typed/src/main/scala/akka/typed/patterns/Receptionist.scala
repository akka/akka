/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.patterns

import akka.typed.ScalaDSL._
import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.Terminated
import akka.util.TypedMultiMap

/**
 * A Receptionist is an entry point into an Actor hierarchy where select Actors
 * publish their identity together with the protocols that they implement. Other
 * Actors need only know the Receptionist’s identity in order to be able to use
 * the services of the registered Actors.
 */
object Receptionist {

  /**
   * Internal representation of [[Receptionist.ServiceKey]] which is needed
   * in order to use a TypedMultiMap (using keys with a type parameter does not
   * work in Scala 2.x).
   */
  trait AbstractServiceKey {
    type Type
  }

  /**
   * A service key is an object that implements this trait for a given protocol
   * T, meaning that it signifies that the type T is the entry point into the
   * protocol spoken by that service (think of it as the set of first messages
   * that a client could send).
   */
  trait ServiceKey[T] extends AbstractServiceKey {
    final override type Type = T
  }

  /**
   * The set of commands accepted by a Receptionist.
   */
  sealed trait Command
  /**
   * Associate the given [[akka.typed.ActorRef]] with the given [[ServiceKey]]. Multiple
   * registrations can be made for the same key. Unregistration is implied by
   * the end of the referenced Actor’s lifecycle.
   */
  final case class Register[T](key: ServiceKey[T], address: ActorRef[T])(val replyTo: ActorRef[Registered[T]]) extends Command
  /**
   * Query the Receptionist for a list of all Actors implementing the given
   * protocol.
   */
  final case class Find[T](key: ServiceKey[T])(val replyTo: ActorRef[Listing[T]]) extends Command

  /**
   * Confirmation that the given [[akka.typed.ActorRef]] has been associated with the [[ServiceKey]].
   */
  final case class Registered[T](key: ServiceKey[T], address: ActorRef[T])
  /**
   * Current listing of all Actors that implement the protocol given by the [[ServiceKey]].
   */
  final case class Listing[T](key: ServiceKey[T], addresses: Set[ActorRef[T]])

  /**
   * Initial behavior of a receptionist, used to create a new receptionist like in the following:
   *
   * {{{
   * val receptionist: ActorRef[Receptionist.Command] = ctx.spawn(Props(Receptionist.behavior), "receptionist")
   * }}}
   */
  val behavior: Behavior[Command] = behavior(TypedMultiMap.empty[AbstractServiceKey, KV])

  private type KV[K <: AbstractServiceKey] = ActorRef[K#Type]

  private def behavior(map: TypedMultiMap[AbstractServiceKey, KV]): Behavior[Command] = Full {
    case Msg(ctx, r: Register[t]) ⇒
      ctx.watch(r.address)
      r.replyTo ! Registered(r.key, r.address)
      behavior(map.inserted(r.key)(r.address))
    case Msg(ctx, f: Find[t]) ⇒
      val set = map get f.key
      f.replyTo ! Listing(f.key, set)
      Same
    case Sig(ctx, Terminated(ref)) ⇒
      behavior(map valueRemoved ref)
  }
}

abstract class Receptionist
