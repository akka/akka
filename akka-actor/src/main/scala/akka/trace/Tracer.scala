/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.trace

import akka.actor.{ ActorSystem, ActorRef, DynamicAccess, ExtendedActorSystem }
import com.typesafe.config.Config
import scala.collection.immutable
import scala.reflect.ClassTag

/**
 * Tracers are attached to actor systems using the `akka.tracers` configuration option,
 * specifying a list of fully qualified class names of tracer implementations. For example:
 *
 * {{{
 * akka.tracers = ["com.example.SomeTracer"]
 * }}}
 *
 * Tracer classes must extend [[akka.trace.Tracer]] and have a public constructor
 * which is empty or optionally accepts a [[com.typesafe.config.Config]] parameter.
 * The config object is the same one as used to create the actor system.
 *
 * There are methods to access an attached tracer implementation on an actor system,
 * for tracers that provide user APIs.
 *
 * Accessing a tracer in Scala:
 * {{{
 * Tracer[SomeTracer](system) // throws exception if not found
 *
 * Tracer.find[SomeTracer](system) // returns Option
 * }}}
 *
 * Accessing a tracer in Java:
 * {{{
 * Tracer.exists(system, SomeTracer.class); // returns boolean
 *
 * Tracer.get(system, SomeTracer.class); // throws exception if not found
 * }}}
 */
object Tracer {
  /**
   * Empty placeholder (null) for when there is no trace context.
   */
  val emptyContext: Any = null

  /**
   * INTERNAL API. Determine whether or not tracing is enabled.
   */
  private[akka] def enabled(tracers: immutable.Seq[String], config: Config): Boolean = tracers.nonEmpty

  /**
   * INTERNAL API. Create the tracer(s) for an actor system.
   *
   * Tracer classes must extend [[akka.trace.Tracer]] and have a public constructor
   * which is empty or optionally accepts a [[com.typesafe.config.Config]] parameter.
   * The config object is the same one as used to create the actor system.
   *
   *  - If there are no tracers then a default empty implementation with final methods
   *    is used ([[akka.trace.NoTracer]]).
   *  - If there is exactly one tracer, then it is created and will be called directly.
   *  - If there are two or more tracers, then an [[akka.trace.MultiTracer]] is created to
   *    delegate to the individual tracers and coordinate the trace contexts.
   */
  private[akka] def apply(tracers: immutable.Seq[String], config: Config, dynamicAccess: DynamicAccess): Tracer = {
    tracers.length match {
      case 0 ⇒ new NoTracer
      case 1 ⇒ create(dynamicAccess, config)(tracers.head)
      case _ ⇒ new MultiTracer(tracers map create(dynamicAccess, config))
    }
  }

  /**
   * INTERNAL API. Create a tracer dynamically from a fully qualified class name.
   * Tracer constructors can optionally accept the actor system config.
   */
  private[akka] def create(dynamicAccess: DynamicAccess, config: Config)(fqcn: String): Tracer = {
    val configArg = List(classOf[Config] -> config)
    dynamicAccess.createInstanceFor[Tracer](fqcn, configArg).recoverWith({
      case _: NoSuchMethodException ⇒ dynamicAccess.createInstanceFor[Tracer](fqcn, Nil)
    }).get
  }

  /**
   * Access an attached tracer by class. Returns null if there is no matching tracer.
   */
  def access[T <: Tracer](system: ActorSystem, tracerClass: Class[T]): T = {
    val tracer = system match {
      case actorSystem: ExtendedActorSystem ⇒
        actorSystem.tracer match {
          case multi: MultiTracer ⇒ (multi.tracers find tracerClass.isInstance).orNull
          case single             ⇒ if (tracerClass isInstance single) single else null
        }
      case _ ⇒ null
    }
    tracer.asInstanceOf[T]
  }

  /**
   * Find an attached tracer by class. Returns an Option.
   */
  def find[T <: Tracer](system: ActorSystem, tracerClass: Class[T]): Option[T] =
    Option(access(system, tracerClass))

  /**
   * Find an attached tracer by implicit class tag. Returns an Option.
   */
  def find[T <: Tracer](system: ActorSystem)(implicit tag: ClassTag[T]): Option[T] =
    find(system, tag.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Access an attached tracer by class.
   *
   * @throws IllegalArgumentException if there is no matching tracer
   */
  def apply[T <: Tracer](system: ActorSystem, tracerClass: Class[T]): T =
    access(system, tracerClass) match {
      case null   ⇒ throw new IllegalArgumentException(s"Trying to access non-existent tracer [$tracerClass]")
      case tracer ⇒ tracer
    }

  /**
   * Access an attached tracer by implicit class tag.
   *
   * @throws IllegalArgumentException if there is no matching tracer
   */
  def apply[T <: Tracer](system: ActorSystem)(implicit tag: ClassTag[T]): T =
    apply(system, tag.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Check whether an attached tracer exists, matching by class.
   */
  def exists[T <: Tracer](system: ActorSystem, tracerClass: Class[T]): Boolean =
    access(system, tracerClass) ne null

  /**
   * Java API: Access an attached tracer by class.
   *
   * @throws IllegalArgumentException if there is no matching tracer
   */
  def get[T <: Tracer](system: ActorSystem, tracerClass: Class[T]): T =
    apply(system, tracerClass)
}

/**
 * Akka Trace SPI.
 *
 * '''Important: tracer implementations must be thread-safe and non-blocking.'''
 *
 * There is optional context propagation available to tracers.
 * This can be used to transfer a transaction identifier through a message flow, or similar.
 * In some implementations the context object will not be needed and can simply be null.
 * For remote messages the trace context needs to be serialized to bytes.
 *
 * A local message flow will have the following calls:
 *
 *  - `actorTold` when the message is sent with `!` or `tell`
 *  - `getContext` to transfer optional context with the message
 *  - `setContext` with transfered context before message processing
 *  - `actorReceived` at the beginning of message processing
 *  - `actorCompleted` at the end of message processing
 *  - `clearContext` after message processing is complete
 *
 * A remote message flow will have the following calls:
 *
 *  - `actorTold` when the message is first sent to a remote actor with `!` or `tell`
 *  - `getContext` to transfer optional context with the message
 *  - `serializeContext` to serialize the attached context
 *  - `remoteMessageSent` when the message is being sent over the wire
 *  - `deserializeContext` to deserialize the transfered context
 *  - `setContext` to frame remote message processing
 *  - `remoteMessageReceived` before the message is processed on the receiving node
 *  - `actorTold` when the message is delivered locally on the receiving node
 *  - `getContext` to transfer optional context with local message send
 *  - `clearContext` after remote message processing is complete
 *  - `setContext` with transfered context before message processing
 *  - `actorReceived` at the beginning of message processing on the receiving node
 *  - `actorCompleted` at the end of message processing
 *  - `clearContext` after message processing is complete
 */
abstract class Tracer {
  /**
   * Record actor system started - after system initialisation and start.
   *
   * @param system the [[akka.actor.ActorSystem]] that has started
   */
  def systemStarted(system: ActorSystem): Unit

  /**
   * Record actor system shutdown - on system termination callback.
   *
   * '''Any tracer cleanup and shutdown can also happen at this point.'''
   *
   * @param system the [[akka.actor.ActorSystem]] that has shutdown
   */
  def systemShutdown(system: ActorSystem): Unit

  /**
   * Record actor told - on message send with `!` or `tell`.
   *
   * A call to [[Tracer#getContext]] will be made after a call to `actorTold`
   * when the context is attached to the message.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being told the message
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   */
  def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): Unit

  /**
   * Record actor received - at the beginning of message processing.
   *
   * A call to [[Tracer#setContext]] will be made before a call to `actorReceived`
   * with the context attached on message send.
   *
   * @param actorRef the self [[akka.actor.ActorRef]] of the actor
   * @param message the message object
   * @param sender the sender [[akka.actor.Actor]] (may be dead letters)
   */
  def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef): Unit

  /**
   * Record actor completed - at the end of message processing.
   *
   * @param actorRef the self [[akka.actor.ActorRef]] of the actor
   * @param message the message object
   * @param sender the sender [[akka.actor.Actor]] (may be dead letters)
   */
  def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit

  /**
   * Record remote message sent - when remote message is going over the wire.
   *
   * @param actorRef the recipient [[akka.actor.ActorRef]] of the remote message
   * @param message the message object
   * @param size the size in bytes of the serialized user message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   */
  def remoteMessageSent(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit

  /**
   * Record remote message received - before the processing of the remote message.
   *
   * @param actorRef the recipient [[akka.actor.ActorRef]] of the remote message
   * @param message the (deserialized) message object
   * @param size the size in bytes of the serialized user message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   */
  def remoteMessageReceived(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit

  /**
   * Retrieve the current context object. For example, from a thread-local.
   * The context object will be attached to an appropriate object for transfer
   * and then passed to a corresponding [[Tracer#setContext]] call.
   *
   * @return the current context object
   */
  def getContext(): Any

  /**
   * Set the current context, with the context object returned by [[Tracer#getContext]].
   *
   * @param context the transfered context object
   */
  def setContext(context: Any): Unit

  /**
   * Clear the current context. Matches a call to [[Tracer#setContext]] and together
   * they frame the context. For example, before and after message processing.
   */
  def clearContext(): Unit

  /**
   * Unique identifier used for this tracer when serializing trace contexts.
   * Similar to [[akka.serialization.Serializer#identifier]].
   */
  def identifier: Int

  /**
   * Serialize a context to bytes for remote transfer or persistence.
   *
   * @param system for access to Akka serialization and dynamicAccess
   * @param context the context object to be serialized
   * @return serialized context bytes
   */
  def serializeContext(system: ExtendedActorSystem, context: Any): Array[Byte]

  /**
   * Deserialize a context as bytes, serialized by [[Tracer#serializeContext]].
   *
   * @param system for access to Akka serialization and dynamicAccess
   * @param context the serialized context bytes
   * @return deserialized context object
   */
  def deserializeContext(system: ExtendedActorSystem, context: Array[Byte]): Any
}

/**
 * Wrapper for messages with trace context added.
 *
 * The TracedMessage is put in the place of the actual message for message sends to
 * local and remote actors, if there is a tracer enabled and the tracer returns a
 * non-empty (non-null) trace context.
 *
 * The TracedMessage is unwrapped in ActorCell invoke.
 *
 * Note that the `currentMessage` variable in ActorCell will still contain the
 * traced message wrapper, so that something like Stash will retain the trace
 * context during stash and unstash. This means that extensions to ActorCell need
 * to take this into account and unwrap the traced message appropriately.
 *
 * Traced messages are also unwrapped for remote message receive. The processing of
 * remote messages will be framed by the attached context, and a new traced message
 * wrapper will be created on local message send.
 *
 * Interaction with other internal message wrappers, like DeadLetter and
 * EndpointManager.Send, should be noted. For remote messages the traced message
 * will wrap the original message and be within the Send wrapper used by remoting.
 * But there can also be another traced message wrapper around the Send as well,
 * when this is sent to the internal remoting actors.
 */
case class TracedMessage(message: Any, context: Any)

/**
 * Wrapping and unwrapping traced messages.
 */
object TracedMessage {
  /**
   * Wrap a message as a traced message with the current context,
   * if the trace context is not empty, otherwise leave unwrapped.
   */
  def apply(system: ExtendedActorSystem, message: Any): Any = {
    val context = system.tracer.getContext
    if (context == Tracer.emptyContext) message else TracedMessage(message, context)
  }

  /**
   * Returns the actual message from unwrapping a traced message.
   */
  def unwrap(message: Any): Any = message match {
    case traced: TracedMessage ⇒ traced.message
    case msg                   ⇒ msg
  }

  /**
   * Returns the actual message from unwrapping a traced message,
   * but only tries to unwrap if a tracer is attached.
   */
  def unwrap(system: ExtendedActorSystem, message: Any): Any =
    if (system.hasTracer) unwrap(message) else message

  /**
   * Sets the trace context, if the message is traced.
   */
  def setTracerContext(system: ExtendedActorSystem, message: Any): Unit = message match {
    case traced: TracedMessage ⇒ system.tracer.setContext(traced.context)
    case _                     ⇒
  }
}

/**
 * Default implementation of Tracer that does nothing. Final methods.
 */
final class NoTracer extends Tracer {
  final def systemStarted(system: ActorSystem): Unit = ()
  final def systemShutdown(system: ActorSystem): Unit = ()
  final def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  final def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  final def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  final def remoteMessageSent(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = ()
  final def remoteMessageReceived(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = ()
  final def getContext(): Any = Tracer.emptyContext
  final def setContext(context: Any): Unit = ()
  final def clearContext(): Unit = ()
  final def identifier: Int = 0
  final def serializeContext(system: ExtendedActorSystem, context: Any): Array[Byte] = Array.empty[Byte]
  final def deserializeContext(system: ExtendedActorSystem, context: Array[Byte]): Any = Tracer.emptyContext
}

/**
 * Implementation of Tracer that does nothing by default. Select methods can be overridden.
 */
class EmptyTracer extends Tracer {
  def systemStarted(system: ActorSystem): Unit = ()
  def systemShutdown(system: ActorSystem): Unit = ()
  def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  def remoteMessageSent(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = ()
  def remoteMessageReceived(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = ()
  def getContext(): Any = Tracer.emptyContext
  def setContext(context: Any): Unit = ()
  def clearContext(): Unit = ()
  val defaultIdentifier: Int = scala.util.hashing.MurmurHash3.stringHash(getClass.getName)
  def identifier: Int = defaultIdentifier
  def serializeContext(system: ExtendedActorSystem, context: Any): Array[Byte] = Array.empty[Byte]
  def deserializeContext(system: ExtendedActorSystem, context: Array[Byte]): Any = Tracer.emptyContext
}

/**
 * Abstract Tracer without context propagation.
 */
abstract class NoContextTracer extends Tracer {
  final def getContext(): Any = Tracer.emptyContext
  final def setContext(context: Any): Unit = ()
  final def clearContext(): Unit = ()
  final def identifier: Int = 0
  final def serializeContext(system: ExtendedActorSystem, context: Any): Array[Byte] = Array.empty[Byte]
  final def deserializeContext(system: ExtendedActorSystem, context: Array[Byte]): Any = Tracer.emptyContext
}

/**
 * Abstract Tracer with context propagation only.
 */
abstract class ContextOnlyTracer extends Tracer {
  final def systemStarted(system: ActorSystem): Unit = ()
  final def systemShutdown(system: ActorSystem): Unit = ()
  final def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  final def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  final def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  final def remoteMessageSent(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = ()
  final def remoteMessageReceived(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = ()
}
