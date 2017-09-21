package akka.typed.receptionist

import akka.typed.ActorRef
import akka.typed.ActorSystem
import akka.typed.Extension
import akka.typed.ExtensionId
import akka.typed.internal.receptionist.LocalReceptionist

import scala.concurrent.duration._
import scala.reflect.ClassTag

class ReceptionistExt(system: ActorSystem[_]) extends Extension {
  val receptionist: ActorRef[Receptionist.Command] =
    ActorRef(
      system.systemActorOf(
        LocalReceptionist.behavior // FIXME: either create local or cluster version depending on configuration
        , "receptionist")(
        // FIXME: where should that timeout be configured? Shouldn't there be a better `Extension`
        //        implementation that does this dance for us?

        10.seconds
      ))
}

/**
 * A Receptionist is an entry point into an Actor hierarchy where select Actors
 * publish their identity together with the protocols that they implement. Other
 * Actors need only know the Receptionist’s identity in order to be able to use
 * the services of the registered Actors.
 */
object Receptionist extends ExtensionId[ReceptionistExt] {
  def createExtension(system: ActorSystem[_]): ReceptionistExt = new ReceptionistExt(system)

  private[typed] abstract class AbstractServiceKey {
    type Protocol
  }

  /**
   * A service key is an object that implements this trait for a given protocol
   * T, meaning that it signifies that the type T is the entry point into the
   * protocol spoken by that service (think of it as the set of first messages
   * that a client could send).
   */
  trait ServiceKey[T] extends AbstractServiceKey {
    final type Protocol = T
    def id: String
  }

  /**
   * Creates a service key. The given ID should uniquely define a service with a given protocol.
   */
  def key[T](_id: String)(implicit tTag: ClassTag[T]): ServiceKey[T] = new ServiceKey[T] {
    def id: String = _id

    override def toString: String = s"ServiceKey[$tTag]($id)"
  }

  sealed abstract class AllCommands

  /**
   * The set of commands accepted by a Receptionist.
   */
  sealed abstract class Command extends AllCommands
  private[typed] abstract class InternalCommand extends AllCommands

  /**
   * Associate the given [[akka.typed.ActorRef]] with the given [[ServiceKey]]. Multiple
   * registrations can be made for the same key. Unregistration is implied by
   * the end of the referenced Actor’s lifecycle.
   */
  final case class Register[T](key: ServiceKey[T], address: ActorRef[T], replyTo: ActorRef[Registered[T]]) extends Command
  object Register {
    def apply[T](key: ServiceKey[T], address: ActorRef[T]): ActorRef[Registered[T]] ⇒ Register[T] =
      replyTo ⇒ Register(key, address, replyTo)
  }

  /**
   * Confirmation that the given [[akka.typed.ActorRef]] has been associated with the [[ServiceKey]].
   */
  final case class Registered[T](key: ServiceKey[T], address: ActorRef[T])

  /**
   * Subscribe to service updates.
   */
  final case class Subscribe[T](key: ServiceKey[T], subscriber: ActorRef[Listing[T]]) extends Command

  // FIXME: do we need a Subscribed event?

  /**
   * Query the Receptionist for a list of all Actors implementing the given
   * protocol.
   */
  final case class Find[T](key: ServiceKey[T], replyTo: ActorRef[Listing[T]]) extends Command
  object Find {
    def apply[T](key: ServiceKey[T]): ActorRef[Listing[T]] ⇒ Find[T] =
      replyTo ⇒ Find(key, replyTo)
  }

  /**
   * Current listing of all Actors that implement the protocol given by the [[ServiceKey]].
   */
  final case class Listing[T](key: ServiceKey[T], addresses: Set[ActorRef[T]])
}
