/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ Actor, ActorRef, ActorRefWithCell, ActorSystem, DynamicAccess, ExtendedActorSystem }
import akka.event.Logging.{ Error, Warning }
import akka.event.{ BusLogging, EventStream }
import com.typesafe.config.Config

import scala.reflect.ClassTag
import scala.util.{ Failure, Try }

/**
 * Instrumentation is attached to actor systems using the `akka.instrumentation`
 * configuration option, specifying a fully qualified class name of an
 * instrumentation implementation. For example:
 *
 * {{{
 * akka.instrumentation = "com.example.SomeInstrumentation"
 * }}}
 *
 * Instrumentation classes must extend [[akka.instrument.ActorInstrumentation]]
 * and have a public constructor which is either empty or optionally accepts
 * any of the following parameters, in order:
 * [[akka.actor.DynamicAccess]], [[com.typesafe.config.Config]],
 * [[akka.event.EventStream]], [[akka.instrument.ActorMetadata]].
 * The config object is the same one as used to create the actor system.
 *
 * There are methods to access attached instrumentations on actor systems,
 * to provide user APIs.
 *
 * Accessing instrumentation in Scala:
 * {{{
 * ActorInstrumentation[SomeInstrumentation](system) // throws exception if not found
 *
 * ActorInstrumentation.find[SomeInstrumentation](system) // returns Option
 * }}}
 *
 * Accessing instrumentation in Java:
 * {{{
 * ActorInstrumentation.exists(system, SomeInstrumentation.class); // returns boolean
 *
 * ActorInstrumentation.get(system, SomeInstrumentation.class); // throws exception if not found
 * }}}
 */
object ActorInstrumentation {
  /**
   * Empty placeholder (null) for when there is no context.
   */
  val EmptyContext: AnyRef = null

  /**
   * INTERNAL API. Create the instrumentation for an actor system.
   *
   * Instrumentation classes must extend [[akka.instrument.ActorInstrumentation]]
   * and have a public constructor which is empty or optionally accepts an
   * [[akka.actor.DynamicAccess]] and/or a [[com.typesafe.config.Config]]
   * parameter. The config object is the same one as used to create the actor
   * system.
   *
   *   - If there is no instrumentation then a default empty implementation
   *     with final methods is used ([[akka.instrument.NoActorInstrumentation]]).
   */
  private[akka] def apply(instrumentations: List[String], dynamicAccess: DynamicAccess, config: Config, eventStream: EventStream): (ActorInstrumentation, Boolean) = {
    instrumentations.length match {
      case 0 ⇒ create("akka.instrument.NoActorInstrumentation", dynamicAccess, config, eventStream) -> false // Use reflection to allow JIT optimizations
      case 1 ⇒ create(instrumentations.head, dynamicAccess, config, eventStream) -> true
      case _ ⇒ create("akka.instrument.Ensemble", dynamicAccess, config, eventStream) -> true // Use reflection to allow JIT optimizations
    }
  }

  /**
   * Create an instrumentation dynamically from a fully qualified class name.
   * Instrumentation constructors can optionally accept the dynamic access
   * and actor system config, or only actor system config.
   *
   * @param instrumentation fully qualified class name of an instrumentation implementation
   * @param dynamicAccess the dynamic access instance used to load the instrumentation class
   * @param config the config object used to initialize the instrumentation
   * @param log a logging adapter that is used to signal exceptions - defaults to null since there might not always be a logging adapter around
   */
  def create(instrumentation: String, dynamicAccess: DynamicAccess, config: Config, eventStream: EventStream, metadata: ActorMetadata = DefaultActorMetadata): ActorInstrumentation = {
    val log = new BusLogging(eventStream, instrumentation, this.getClass)
    val args = List(classOf[DynamicAccess] -> dynamicAccess, classOf[Config] -> config, classOf[EventStream] -> eventStream, classOf[ActorMetadata] -> metadata)
    val combinations = (args.size to 0 by -1).flatMap(args.combinations) // in-order argument combinations from all to none
    (combinations.foldLeft(Failure(new NoSuchMethodException): Try[ActorInstrumentation]) {
      case (result, nextArgs) ⇒ result recoverWith {
        case _: NoSuchMethodException ⇒ dynamicAccess.createInstanceFor[ActorInstrumentation](instrumentation, nextArgs)
      }
    } recoverWith {
      case d: DisabledException ⇒
        val message = d.getMessage
        // Allow the instrumentation to take care of all logging by setting the message to ""
        if (message.length > 0)
          log.info("Actor instrumentation is disabled. {}", d.getMessage)
        dynamicAccess.createInstanceFor[ActorInstrumentation]("akka.instrument.NoActorInstrumentation", Nil)
      case e: Exception ⇒
        log.warning("Cannot create actor instrumentation. {}", e.getMessage)
        dynamicAccess.createInstanceFor[ActorInstrumentation]("akka.instrument.NoActorInstrumentation", Nil)
    }).get
  }

  /**
   * Access attached instrumentation by class. Returns null if there is no matching instrumentation.
   */
  def access[T <: ActorInstrumentation](system: ActorSystem, instrumentationClass: Class[T]): T = system match {
    case actorSystem: ExtendedActorSystem ⇒ actorSystem.instrumentation.access(instrumentationClass)
    case _                                ⇒ null.asInstanceOf[T]
  }

  /**
   * Find attached instrumentation by class. Returns an Option.
   */
  def find[T <: ActorInstrumentation](system: ActorSystem, instrumentationClass: Class[T]): Option[T] =
    Option(access(system, instrumentationClass))

  /**
   * Find attached instrumentation by implicit class tag. Returns an Option.
   */
  def find[T <: ActorInstrumentation](system: ActorSystem)(implicit tag: ClassTag[T]): Option[T] =
    find(system, tag.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Access attached instrumentation by class.
   *
   * @throws IllegalArgumentException if there is no matching instrumentation
   */
  def apply[T <: ActorInstrumentation](system: ActorSystem, instrumentationClass: Class[T]): T =
    access(system, instrumentationClass) match {
      case null            ⇒ throw new IllegalArgumentException(s"Trying to access non-existent instrumentation [$instrumentationClass]")
      case instrumentation ⇒ instrumentation
    }

  /**
   * Access attached instrumentation by implicit class tag.
   *
   * @throws IllegalArgumentException if there is no matching instrumentation
   */
  def apply[T <: ActorInstrumentation](system: ActorSystem)(implicit tag: ClassTag[T]): T =
    apply(system, tag.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Check whether attached instrumentation exists, matching by class.
   */
  def exists[T <: ActorInstrumentation](system: ActorSystem, instrumentationClass: Class[T]): Boolean =
    access(system, instrumentationClass) ne null

  /**
   * Java API: Access attached instrumentation by class.
   *
   * @throws IllegalArgumentException if there is no matching instrumentation
   */
  def get[T <: ActorInstrumentation](system: ActorSystem, instrumentationClass: Class[T]): T =
    apply(system, instrumentationClass)

  /**
   * Exception thrown to signal that the instrumentation has been disabled in a controlled manner.
   */
  final class DisabledException(message: String) extends Exception(message)
}

/**
 * Akka Instrumentation SPI.
 *
 * '''Important: instrumentation implementations must be thread-safe and non-blocking.'''
 *
 * There is optional context propagation available to instrumentations.
 * This can be used to transfer a trace identifier through a message flow, or similar.
 * In some implementations the context object will not be needed and can simply be null.
 *
 * A message flow will have the following calls:
 *
 *  - `actorTold` when the message is sent with `!` or `tell`, returns an optional context
 *  - `actorReceived` at the beginning of message processing, with optional context
 *  - `actorCompleted` at the end of message processing, with optional context
 *  - `clearContext` after message processing is complete
 */
abstract class ActorInstrumentation {
  /**
   * Access this instrumentation by class.
   * Returns null if the instrumentation doesn't match.
   */
  def access[T <: ActorInstrumentation](instrumentationClass: Class[T]): T

  /**
   * Record actor system started - after system initialisation and start.
   *
   * @param system the [[akka.actor.ActorSystem]] that has started
   */
  def systemStarted(system: ActorSystem): Unit

  /**
   * Record actor system shutdown - on system termination callback.
   *
   * '''Any instrumentation cleanup and shutdown can also happen at this point.'''
   *
   * @param system the [[akka.actor.ActorSystem]] that has shutdown
   */
  def systemShutdown(system: ActorSystem): Unit

  /**
   * Record actor created - before the actor is started in the context of the
   * creating thread. The actor isn't fully initialized yet, and you can't
   * send messages to it.
   *
   * FIXME: We can't easily get hold of the creating actor without changing a
   * lot in the akka code, but the call happens in the context of the creating
   * actor, so if there is a context attached it can be used to access
   * information.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being started
   */
  def actorCreated(actorRef: ActorRef): Unit

  /**
   * Record actor started.
   *
   * FIXME: There is no tracing context available when this method is called,
   * since we don't propagate contexts with system messages. Would also be
   * useful to provide who started the actor.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being started
   */
  def actorStarted(actorRef: ActorRef): Unit

  /**
   * Record actor stopped.
   *
   * FIXME: There is no tracing context available when this method is called,
   * since we don't propagate contexts with system messages. Would also be
   * useful to provide who stopped the actor.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being stopped
   */
  def actorStopped(actorRef: ActorRef): Unit

  /**
   * Record actor told - on message send with `!` or `tell`.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being told the message
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   * @return the context that will travel with this message and picked up by `actorReceived`
   */
  def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef

  /**
   * Record actor received - at the beginning of message processing.
   *
   * @param actorRef the self [[akka.actor.ActorRef]] of the actor
   * @param message the message object
   * @param sender the sender [[akka.actor.Actor]] (may be dead letters)
   * @param context the context associated with this message (from `actorTold`)
   * @return the context that will be transferred to `actorCompleted`
   */
  def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef

  /**
   * Record actor completed - at the end of message processing.
   *
   * @param actorRef the self [[akka.actor.ActorRef]] of the actor
   * @param message the message object
   * @param sender the sender [[akka.actor.Actor]] (may be dead letters)
   * @param context the context associated with this message processing (from `actorReceived`)
   */
  def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit

  /**
   * Clear the current context - when message context is no longer in scope.
   */
  def clearContext(): Unit

  /**
   * Record unhandled message - when the unhandled message is received.
   *
   * @param actorRef the [[akka.actor.ActorRef]] that didn't handle the message
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   */
  def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit

  /**
   * Record message going to dead letters - when the message is being sent.
   *
   * @param actorRef the [[akka.actor.ActorRef]] that is no longer available
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   */
  def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit

  /**
   * Record logging of warning - when the warning is being logged.
   * FIXME should we really have case classes here?
   *
   * @param actorRef the [[akka.actor.ActorRef]] logging the warning or Actor.noSender
   * @param warning the warning being logged
   */
  def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit

  /**
   * Record logging of error - when the error is being logged.
   * FIXME should we really have case classes here?
   *
   * @param actorRef the [[akka.actor.ActorRef]] logging the error or Actor.noSender
   * @param error the error being logged
   */
  def eventLogError(actorRef: ActorRef, error: Error): Unit

  /**
   * Record actor failure - before the failure is propagated to the supervisor.
   *
   * @param actorRef the [[akka.actor.ActorRef]] that has failed
   * @param cause the [[java.lang.Throwable]] cause of the failure
   */
  def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit
}

/**
 * Implementation of ActorInstrumentation that does nothing by default. Select methods can be overridden.
 */
abstract class EmptyActorInstrumentation extends ActorInstrumentation {
  override def access[T <: ActorInstrumentation](instrumentationClass: Class[T]): T =
    (if (instrumentationClass isInstance this) this else null).asInstanceOf[T]

  override def systemStarted(system: ActorSystem): Unit = ()
  override def systemShutdown(system: ActorSystem): Unit = ()

  override def actorCreated(actorRef: ActorRef): Unit = ()
  override def actorStarted(actorRef: ActorRef): Unit = ()
  override def actorStopped(actorRef: ActorRef): Unit = ()

  override def actorTold(receiver: ActorRef, message: Any, sender: ActorRef): AnyRef = ActorInstrumentation.EmptyContext
  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = ActorInstrumentation.EmptyContext
  override def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit = ()

  override def clearContext(): Unit = ()

  override def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  override def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  override def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit = ()
  override def eventLogError(actorRef: ActorRef, error: Error): Unit = ()
  override def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit = ()
}

/**
 * Final implementation of ActorInstrumentation that does nothing.
 */
final class NoActorInstrumentation extends EmptyActorInstrumentation

/**
 * Convenience class to attach and extract metadata objects for ActorRefs.
 */
class ActorMetadata {
  /**
   * Extract the actor class from an ActorRef, if it has an underlying cell.
   * @return actor class
   */
  def actorClass(actorRef: ActorRef): Class[_ <: Actor] = actorRef match {
    case actorRefWithCell: ActorRefWithCell ⇒ actorRefWithCell.underlying.props.actorClass
    case _                                  ⇒ classOf[Actor]
  }

  /**
   * Attach a metadata object to an ActorRef, if it has an underlying cell.
   */
  def attachTo(actorRef: ActorRef, metadata: AnyRef): Unit = actorRef match {
    case actorRefWithCell: ActorRefWithCell ⇒ actorRefWithCell.metadata = metadata
    case _                                  ⇒
  }

  /**
   * Extract the metadata object from an ActorRef, if it has an underlying cell.
   * @return attached metadata, otherwise null
   */
  def extractFrom(actorRef: ActorRef): AnyRef = actorRef match {
    case actorRefWithCell: ActorRefWithCell ⇒ actorRefWithCell.metadata
    case _                                  ⇒ null
  }

  /**
   * Clear the metadata object from an ActorRef, if it has an underlying cell.
   * @return attached metadata, otherwise null
   */
  def removeFrom(actorRef: ActorRef): AnyRef = actorRef match {
    case actorRefWithCell: ActorRefWithCell ⇒
      val metadata = actorRefWithCell.metadata
      actorRefWithCell.metadata = null
      metadata
    case _ ⇒ null
  }
}

object DefaultActorMetadata extends ActorMetadata
