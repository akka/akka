/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed.receptionist

import akka.annotation.{ DoNotInherit, InternalApi }
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.internal.receptionist.ReceptionistBehaviorProvider
import akka.actor.typed.internal.receptionist.ReceptionistImpl
import akka.actor.typed.receptionist.Receptionist.{ Find, Registered }

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.reflect.ClassTag

class Receptionist(system: ActorSystem[_]) extends Extension {

  private def hasCluster: Boolean = {
    // FIXME: replace with better indicator that cluster is enabled
    val provider = system.settings.config.getString("akka.actor.provider")
    (provider == "cluster") || (provider == "akka.cluster.ClusterActorRefProvider")
  }

  val ref: ActorRef[Receptionist.Command] = {
    val behavior =
      if (hasCluster)
        system.dynamicAccess
          .createInstanceFor[ReceptionistBehaviorProvider]("akka.cluster.typed.internal.receptionist.ClusterReceptionist$", Nil)
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

object ServiceKey {
  /**
   * Scala API: Creates a service key. The given ID should uniquely define a service with a given protocol.
   */
  def apply[T: ClassTag](id: String): ServiceKey[T] =
    ReceptionistImpl.DefaultServiceKey(id, implicitly[ClassTag[T]].runtimeClass.getName)

  /**
   * Java API: Creates a service key. The given ID should uniquely define a service with a given protocol.
   */
  def create[T](clazz: Class[T], id: String): ServiceKey[T] =
    ReceptionistImpl.DefaultServiceKey(id, clazz.getName)

}

/**
 * A service key is an object that implements this trait for a given protocol
 * T, meaning that it signifies that the type T is the entry point into the
 * protocol spoken by that service (think of it as the set of first messages
 * that a client could send).
 */
abstract class ServiceKey[T] extends Receptionist.AbstractServiceKey { key ⇒
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
  private[akka] sealed abstract class AbstractServiceKey {
    type Protocol

    /** Type-safe down-cast */
    def asServiceKey: ServiceKey[Protocol]
  }

  /** Internal superclass for external and internal commands */
  @InternalApi
  sealed private[akka] abstract class AllCommands

  /**
   * The set of commands accepted by a Receptionist.
   */
  sealed abstract class Command extends AllCommands
  @InternalApi
  private[typed] abstract class InternalCommand extends AllCommands

  /**
   * Internal API
   */
  @InternalApi
  object MessageImpls {
    // some trixery here to provide a nice _and_ safe API in the face
    // of type erasure, more type safe factory methods for each message
    // is the user API below while still hiding the type parameter so that
    // users don't incorrecly match against it

    final case class Register[T] private[akka] (
      key:             ServiceKey[T],
      serviceInstance: ActorRef[T],
      replyTo:         Option[ActorRef[Receptionist.Registered]]) extends Command

    final case class Registered[T] private[akka] (key: ServiceKey[T], _serviceInstance: ActorRef[T]) extends Receptionist.Registered {
      def isForKey(key: ServiceKey[_]): Boolean = key == this.key
      def serviceInstance[M](key: ServiceKey[M]): ActorRef[M] = {
        if (key != this.key) throw new IllegalArgumentException(s"Wrong key [$key] used, must use listing key [${this.key}]")
        _serviceInstance.asInstanceOf[ActorRef[M]]
      }

      def getServiceInstance[M](key: ServiceKey[M]): ActorRef[M] =
        serviceInstance(key)
    }

    final case class Find[T] private[akka] (key: ServiceKey[T], replyTo: ActorRef[Receptionist.Listing]) extends Command

    final case class Listing[T] private[akka] (key: ServiceKey[T], _serviceInstances: Set[ActorRef[T]]) extends Receptionist.Listing {

      def isForKey(key: ServiceKey[_]): Boolean = key == this.key

      def serviceInstances[M](key: ServiceKey[M]): Set[ActorRef[M]] = {
        if (key != this.key) throw new IllegalArgumentException(s"Wrong key [$key] used, must use listing key [${this.key}]")
        _serviceInstances.asInstanceOf[Set[ActorRef[M]]]
      }

      def getServiceInstances[M](key: ServiceKey[M]): java.util.Set[ActorRef[M]] =
        serviceInstances(key).asJava
    }

    final case class Subscribe[T] private[akka] (key: ServiceKey[T], subscriber: ActorRef[Receptionist.Listing]) extends Command

  }

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
      new MessageImpls.Register[T](key, service, None)
    /**
     * Create a Register with an actor that will get an ack that the service was registered
     */
    def apply[T](key: ServiceKey[T], service: ActorRef[T], replyTo: ActorRef[Registered]): Command =
      new MessageImpls.Register[T](key, service, Some(replyTo))
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
   * Can not be pattern matched from Scala, use [[ServiceKey.Registered]] instead
   */
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
      new MessageImpls.Registered(key, serviceInstance)

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
      new MessageImpls.Subscribe(key, subscriber)

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
      new MessageImpls.Find(key, replyTo)

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
   * Can not be pattern matched from Scala, use [[ServiceKey.Listing]] instead
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
      new MessageImpls.Listing[T](key, serviceInstances)

  }

  /**
   * Java API: Sent by the receptionist, available here for easier testing
   */
  def listing[T](key: ServiceKey[T], serviceInstances: java.util.Set[ActorRef[T]]): Listing =
    Listing(key, Set[ActorRef[T]](serviceInstances.asScala.toSeq: _*))

}
