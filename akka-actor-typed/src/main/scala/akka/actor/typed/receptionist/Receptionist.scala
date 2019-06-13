/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.receptionist

import akka.actor.typed.{ ActorRef, ActorSystem, Dispatchers, Extension, ExtensionId, ExtensionSetup, Props }
import akka.actor.typed.internal.receptionist._
import akka.annotation.DoNotInherit

import akka.util.ccompat.JavaConverters._
import scala.reflect.ClassTag
import akka.annotation.InternalApi

/**
 * This class is not intended for user extension other than for test purposes (e.g.
 * stub implementation). More methods may be added in the future and that may break
 * such implementations.
 */
@DoNotInherit
abstract class Receptionist extends Extension {
  def ref: ActorRef[Receptionist.Command]
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class ReceptionistImpl(system: ActorSystem[_]) extends Receptionist {

  override val ref: ActorRef[Receptionist.Command] = {
    val provider: ReceptionistBehaviorProvider =
      if (system.settings.untypedSettings.ProviderSelectionType.hasCluster) {
        system.dynamicAccess
          .getObjectFor[ReceptionistBehaviorProvider]("akka.cluster.typed.internal.receptionist.ClusterReceptionist")
          .recover {
            case e =>
              throw new RuntimeException(
                "ClusterReceptionist could not be loaded dynamically. Make sure you have " +
                "'akka-cluster-typed' in the classpath.",
                e)
          }
          .get
      } else LocalReceptionist

    import akka.actor.typed.scaladsl.adapter._
    system.internalSystemActorOf(
      provider.behavior,
      provider.name,
      Props.empty.withDispatcherFromConfig(Dispatchers.InternalDispatcherId))
  }
}

object ServiceKey {

  /**
   * Scala API: Creates a service key. The given ID should uniquely define a service with a given protocol.
   */
  def apply[T](id: String)(implicit classTag: ClassTag[T]): ServiceKey[T] =
    DefaultServiceKey(id, classTag.runtimeClass.getName)

  /**
   * Java API: Creates a service key. The given ID should uniquely define a service with a given protocol.
   */
  def create[T](clazz: Class[T], id: String): ServiceKey[T] =
    DefaultServiceKey(id, clazz.getName)

}

/**
 * A service key is an object that implements this trait for a given protocol
 * T, meaning that it signifies that the type T is the entry point into the
 * protocol spoken by that service (think of it as the set of first messages
 * that a client could send).
 *
 * Not for user extension, see factories in companion object: [[ServiceKey#create]] and [[ServiceKey#apply]]
 */
@DoNotInherit
abstract class ServiceKey[T] extends AbstractServiceKey { key =>
  type Protocol = T
  def id: String
  def asServiceKey: ServiceKey[T] = this

  /**
   * Scala API: Provides a type safe pattern match for listings
   */
  object Listing {
    def unapply(l: Receptionist.Listing): Option[Set[ActorRef[T]]] =
      if (l.isForKey(key)) Some(l.serviceInstances(key))
      else None
  }

  /**
   * Scala API: Provides a type safe pattern match for registration acks
   */
  object Registered {
    def unapply(l: Receptionist.Registered): Option[ActorRef[T]] =
      if (l.isForKey(key)) Some(l.serviceInstance(key))
      else None
  }
}

/**
 * A Receptionist is an entry point into an Actor hierarchy where select Actors
 * publish their identity together with the protocols that they implement. Other
 * Actors need only know the Receptionist’s identity in order to be able to use
 * the services of the registered Actors.
 *
 * These are the messages (and the extension) for interacting with the receptionist.
 * The receptionist is easiest accessed through the system: [[ActorSystem.receptionist]]
 */
object Receptionist extends ExtensionId[Receptionist] {
  def createExtension(system: ActorSystem[_]): Receptionist = new ReceptionistImpl(system)
  def get(system: ActorSystem[_]): Receptionist = apply(system)

  /**
   * The set of commands accepted by a Receptionist.
   *
   * Not for user Extension
   */
  @DoNotInherit abstract class Command

  /**
   * Associate the given [[akka.actor.typed.ActorRef]] with the given [[ServiceKey]]. Multiple
   * registrations can be made for the same key. De-registration is implied by
   * the end of the referenced Actor’s lifecycle.
   *
   * Registration will be acknowledged with the [[Registered]] message to the given replyTo actor
   * if there is one.
   */
  object Register {

    /**
     * Create a Register without Ack that the service was registered
     */
    def apply[T](key: ServiceKey[T], service: ActorRef[T]): Command =
      new ReceptionistMessages.Register[T](key, service, None)

    /**
     * Create a Register with an actor that will get an ack that the service was registered
     */
    def apply[T](key: ServiceKey[T], service: ActorRef[T], replyTo: ActorRef[Registered]): Command =
      new ReceptionistMessages.Register[T](key, service, Some(replyTo))
  }

  /**
   * Java API: A Register message without Ack that the service was registered
   */
  def register[T](key: ServiceKey[T], service: ActorRef[T]): Command = Register(key, service)

  /**
   * Java API: A Register message with Ack that the service was registered
   */
  def register[T](key: ServiceKey[T], service: ActorRef[T], replyTo: ActorRef[Registered]): Command =
    Register(key, service, replyTo)

  /**
   * Confirmation that the given [[akka.actor.typed.ActorRef]] has been associated with the [[ServiceKey]].
   *
   * You can use `key.Registered` for type-safe pattern matching.
   *
   * Not for user extension
   */
  @DoNotInherit
  trait Registered {

    def isForKey(key: ServiceKey[_]): Boolean

    /** Scala API */
    def key: ServiceKey[_]

    /** Java API */
    def getKey: ServiceKey[_] = key

    /**
     * Scala API
     *
     * Also, see [[ServiceKey.Listing]] for more convenient pattern matching
     */
    def serviceInstance[T](key: ServiceKey[T]): ActorRef[T]

    /** Java API */
    def getServiceInstance[T](key: ServiceKey[T]): ActorRef[T]
  }

  /**
   * Sent by the receptionist, available here for easier testing
   */
  object Registered {

    /**
     * Scala API
     */
    def apply[T](key: ServiceKey[T], serviceInstance: ActorRef[T]): Registered =
      new ReceptionistMessages.Registered(key, serviceInstance)

  }

  /**
   * Java API: Sent by the receptionist, available here for easier testing
   */
  def registered[T](key: ServiceKey[T], serviceInstance: ActorRef[T]): Registered =
    Registered(key, serviceInstance)

  /**
   * Subscribe the given actor to service updates. When new instances are registered or unregistered to the given key
   * the given subscriber will be sent a [[Listing]] with the new set of instances for that service.
   *
   * The subscription will be acknowledged by sending out a first [[Listing]]. The subscription automatically ends
   * with the termination of the subscriber.
   */
  object Subscribe {

    /**
     * Scala API:
     */
    def apply[T](key: ServiceKey[T], subscriber: ActorRef[Listing]): Command =
      new ReceptionistMessages.Subscribe(key, subscriber)

  }

  /**
   * Java API: Subscribe the given actor to service updates. When new instances are registered or unregistered to the given key
   * the given subscriber will be sent a [[Listing]] with the new set of instances for that service.
   *
   * The subscription will be acknowledged by sending out a first [[Listing]]. The subscription automatically ends
   * with the termination of the subscriber.
   */
  def subscribe[T](key: ServiceKey[T], subscriber: ActorRef[Listing]): Command = Subscribe(key, subscriber)

  /**
   * Query the Receptionist for a list of all Actors implementing the given
   * protocol at one point in time.
   */
  object Find {

    /** Scala API: */
    def apply[T](key: ServiceKey[T], replyTo: ActorRef[Listing]): Command =
      new ReceptionistMessages.Find(key, replyTo)

    /**
     * Special factory to make using Find with ask easier
     */
    def apply[T](key: ServiceKey[T]): ActorRef[Listing] => Command = ref => new ReceptionistMessages.Find(key, ref)
  }

  /**
   * Java API: Query the Receptionist for a list of all Actors implementing the given
   * protocol at one point in time.
   */
  def find[T](key: ServiceKey[T], replyTo: ActorRef[Listing]): Command =
    Find(key, replyTo)

  /**
   * Current listing of all Actors that implement the protocol given by the [[ServiceKey]].
   *
   * You can use `key.Listing` for type-safe pattern matching.
   *
   * Not for user extension.
   */
  @DoNotInherit
  trait Listing {

    /** Scala API */
    def key: ServiceKey[_]

    /** Java API */
    def getKey: ServiceKey[_] = key

    def isForKey(key: ServiceKey[_]): Boolean

    /**
     * Scala API
     *
     * Also, see [[ServiceKey.Listing]] for more convenient pattern matching
     */
    def serviceInstances[T](key: ServiceKey[T]): Set[ActorRef[T]]

    /** Java API */
    def getServiceInstances[T](key: ServiceKey[T]): java.util.Set[ActorRef[T]]

  }

  /**
   * Sent by the receptionist, available here for easier testing
   */
  object Listing {

    /** Scala API: */
    def apply[T](key: ServiceKey[T], serviceInstances: Set[ActorRef[T]]): Listing =
      new ReceptionistMessages.Listing[T](key, serviceInstances)

  }

  /**
   * Java API: Sent by the receptionist, available here for easier testing
   */
  def listing[T](key: ServiceKey[T], serviceInstances: java.util.Set[ActorRef[T]]): Listing =
    Listing(key, Set[ActorRef[T]](serviceInstances.asScala.toSeq: _*))

}

object ReceptionistSetup {
  def apply[T <: Extension](createExtension: ActorSystem[_] => Receptionist): ReceptionistSetup =
    new ReceptionistSetup(new java.util.function.Function[ActorSystem[_], Receptionist] {
      override def apply(sys: ActorSystem[_]): Receptionist = createExtension(sys)
    }) // TODO can be simplified when compiled only with Scala >= 2.12

}

/**
 * Can be used in [[akka.actor.setup.ActorSystemSetup]] when starting the [[ActorSystem]]
 * to replace the default implementation of the [[Receptionist]] extension. Intended
 * for tests that need to replace extension with stub/mock implementations.
 */
final class ReceptionistSetup(createExtension: java.util.function.Function[ActorSystem[_], Receptionist])
    extends ExtensionSetup[Receptionist](Receptionist, createExtension)
