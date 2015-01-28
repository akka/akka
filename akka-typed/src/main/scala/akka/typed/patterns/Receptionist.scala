/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed.patterns

import akka.typed.ScalaDSL._
import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.Terminated

/**
 * A Receptionist is an entry point into an Actor hierarchy where select Actors
 * publish their identity together with the protocols that they implement. Other
 * Actors need only know the Receptionist’s identity in order to be able to use
 * the services of the registered Actors.
 */
object Receptionist {

  /**
   * A service key is an object that implements this trait for a given protocol
   * T, meaning that it signifies that the type T is the entry point into the
   * protocol spoken by that service (think of it as the set of first messages
   * that a client could send).
   */
  trait ServiceKey[T]

  /**
   * The set of commands accepted by a Receptionist.
   */
  sealed trait Command
  /**
   * Associate the given [[ActorRef]] with the given [[ServiceKey]]. Multiple
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
   * Confirmtion that the given [[ActorRef]] has been associated with the [[ServiceKey]].
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
  val behavior: Behavior[Command] = behavior(Map.empty)

  /*
   * These wrappers are just there to get around the type madness that would otherwise ensue
   * by declaring a Map[ServiceKey[_], Set[ActorRef[_]]] (and actually trying to use it).
   */
  private class Key(val key: ServiceKey[_]) {
    override def equals(other: Any) = other match {
      case k: Key ⇒ key == k.key
      case _      ⇒ false
    }
    override def hashCode = key.hashCode
  }
  private object Key {
    def apply(r: Register[_]) = new Key(r.key)
    def apply(f: Find[_]) = new Key(f.key)
  }

  private class Address(val address: ActorRef[_]) {
    def extract[T]: ActorRef[T] = address.asInstanceOf[ActorRef[T]]
    override def equals(other: Any) = other match {
      case a: Address ⇒ address == a.address
      case _          ⇒ false
    }
    override def hashCode = address.hashCode
  }
  private object Address {
    def apply(r: Register[_]) = new Address(r.address)
    def apply(r: ActorRef[_]) = new Address(r)
  }

  private def behavior(map: Map[Key, Set[Address]]): Behavior[Command] = Full {
    case Msg(ctx, r: Register[t]) ⇒
      ctx.watch(r.address)
      val key = Key(r)
      val set = map get key match {
        case Some(old) ⇒ old + Address(r)
        case None      ⇒ Set(Address(r))
      }
      r.replyTo ! Registered(r.key, r.address)
      behavior(map.updated(key, set))
    case Msg(ctx, f: Find[t]) ⇒
      val set = map get Key(f) getOrElse Set.empty
      f.replyTo ! Listing(f.key, set.map(_.extract[t]))
      Same
    case Sig(ctx, Terminated(ref)) ⇒
      val addr = Address(ref)
      // this is not at all optimized
      behavior(map.map { case (k, v) ⇒ k -> (v - addr) })
  }
}
