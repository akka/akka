/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.receptionist

import akka.annotation.InternalApi
import akka.typed.ActorRef
import akka.typed.ActorSystem
import akka.typed.Extension
import akka.typed.ExtensionId
import akka.typed.internal.receptionist.ReceptionistBehaviorProvider
import akka.typed.internal.receptionist.ReceptionistImpl

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.reflect.ClassTag

class Receptionist(system: ActorSystem[_]) extends Extension {
  private def hasCluster: Boolean =
    // FIXME: replace with better indicator that cluster is enabled
    system.settings.config.getString("akka.actor.provider") == "cluster"

  val ref: ActorRef[Receptionist.Command] = {
    val behavior =
      if (hasCluster)
        system.dynamicAccess
          .createInstanceFor[ReceptionistBehaviorProvider]("akka.typed.cluster.internal.receptionist.ClusterReceptionist$", Nil)
          .recover {
            case ex ⇒
              system.log.error(
                ex,
                "ClusterReceptionist could not be loaded dynamically. Make sure you have all required binaries on the classpath.")
              ReceptionistImpl
          }.get.behavior

      else ReceptionistImpl.localOnlyBehavior

    ActorRef(
      system.systemActorOf(behavior, "receptionist")(
        // FIXME: where should that timeout be configured? Shouldn't there be a better `Extension`
        //        implementation that does this dance for us?

        10.seconds))
  }
}

/**
 * A Receptionist is an entry point into an Actor hierarchy where select Actors
 * publish their identity together with the protocols that they implement. Other
 * Actors need only know the Receptionist’s identity in order to be able to use
 * the services of the registered Actors.
 */
object Receptionist extends ExtensionId[Receptionist] {
  def createExtension(system: ActorSystem[_]): Receptionist = new Receptionist(system)
  def get(system: ActorSystem[_]): Receptionist = apply(system)

  /**
   * Internal representation of [[ServiceKey]] which is needed
   * in order to use a TypedMultiMap (using keys with a type parameter does not
   * work in Scala 2.x).
   *
   * Internal API
   */
  @InternalApi
  private[typed] sealed abstract class AbstractServiceKey {
    type Protocol

    /** Type-safe down-cast */
    def asServiceKey: ServiceKey[Protocol]
  }

  /**
   * A service key is an object that implements this trait for a given protocol
   * T, meaning that it signifies that the type T is the entry point into the
   * protocol spoken by that service (think of it as the set of first messages
   * that a client could send).
   */
  abstract class ServiceKey[T] extends AbstractServiceKey {
    final type Protocol = T
    def id: String
    def asServiceKey: ServiceKey[T] = this
  }

  object ServiceKey {
    /**
     * Scala API: Creates a service key. The given ID should uniquely define a service with a given protocol.
     */
    def apply[T](id: String)(implicit tTag: ClassTag[T]): ServiceKey[T] =
      ReceptionistImpl.DefaultServiceKey(id, implicitly[ClassTag[T]].runtimeClass.getName)

    /**
     * Java API: Creates a service key. The given ID should uniquely define a service with a given protocol.
     */
    def create[T](clazz: Class[T], id: String): ServiceKey[T] =
      ReceptionistImpl.DefaultServiceKey(id, clazz.getName)

  }

  /** Internal superclass for external and internal commands */
  @InternalApi
  sealed private[typed] abstract class AllCommands

  /**
   * The set of commands accepted by a Receptionist.
   */
  sealed abstract class Command extends AllCommands
  @InternalApi
  private[typed] abstract class InternalCommand extends AllCommands

  /**
   * Associate the given [[akka.typed.ActorRef]] with the given [[ServiceKey]]. Multiple
   * registrations can be made for the same key. Unregistration is implied by
   * the end of the referenced Actor’s lifecycle.
   *
   * Registration will be acknowledged with the [[Registered]] message to the given replyTo actor.
   */
  final case class Register[T](key: ServiceKey[T], serviceInstance: ActorRef[T], replyTo: ActorRef[Registered[T]]) extends Command
  object Register {
    /** Auxiliary constructor to be used with the ask pattern */
    def apply[T](key: ServiceKey[T], service: ActorRef[T]): ActorRef[Registered[T]] ⇒ Register[T] =
      replyTo ⇒ Register(key, service, replyTo)
  }

  /**
   * Confirmation that the given [[akka.typed.ActorRef]] has been associated with the [[ServiceKey]].
   */
  final case class Registered[T](key: ServiceKey[T], serviceInstance: ActorRef[T])

  /**
   * Subscribe the given actor to service updates. When new instances are registered or unregistered to the given key
   * the given subscriber will be sent a [[Listing]] with the new set of instances for that service.
   *
   * The subscription will be acknowledged by sending out a first [[Listing]]. The subscription automatically ends
   * with the termination of the subscriber.
   */
  final case class Subscribe[T](key: ServiceKey[T], subscriber: ActorRef[Listing[T]]) extends Command

  /**
   * Query the Receptionist for a list of all Actors implementing the given
   * protocol.
   */
  final case class Find[T](key: ServiceKey[T], replyTo: ActorRef[Listing[T]]) extends Command
  object Find {
    /** Auxiliary constructor to use with the ask pattern */
    def apply[T](key: ServiceKey[T]): ActorRef[Listing[T]] ⇒ Find[T] =
      replyTo ⇒ Find(key, replyTo)
  }

  /**
   * Current listing of all Actors that implement the protocol given by the [[ServiceKey]].
   */
  final case class Listing[T](key: ServiceKey[T], serviceInstances: Set[ActorRef[T]]) {
    /** Java API */
    def getServiceInstances: java.util.Set[ActorRef[T]] = serviceInstances.asJava
  }
}
