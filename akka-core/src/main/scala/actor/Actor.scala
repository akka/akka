/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.serialization.Serializer

import com.google.protobuf.Message

/**
 * Implements the Transactor abstraction. E.g. a transactional actor.
 * <p/>
 * Equivalent to invoking the <code>makeTransactionRequired</code> method in the body of the <code>Actor</code
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Transactor extends Actor {
  self.makeTransactionRequired
}

/**
 * Extend this abstract class to create a remote actor.
 * <p/>
 * Equivalent to invoking the <code>makeRemote(..)</code> method in the body of the <code>Actor</code
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class RemoteActor(hostname: String, port: Int) extends Actor {
  self.makeRemote(hostname, port)
}

/**
 * Mix in this trait to create a serializable actor, serializable through 
 * a custom serialization protocol.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait SerializableActor[T] extends Actor {
  val serializer: Serializer
  def toBinary: Array[Byte]
}

/**
 * Mix in this trait to create a serializable actor, serializable through 
 * Protobuf. This trait needs to be mixed in with a Protobuf 
 * 'com.google.protobuf.Message' generated class holding the state.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ProtobufSerializableActor[T] extends SerializableActor[T] { this: Message => 
  val serializer = Serializer.Protobuf
  def toBinary: Array[Byte] = this.toByteArray
}

/**
 * Mix in this trait to create a serializable actor, serializable through 
 * Java serialization.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait JavaSerializableActor[T] extends SerializableActor[T] {
  val serializer = Serializer.Java
  def toBinary: Array[Byte] = serializer.toBinary(this)
}

/**
 * Mix in this trait to create a serializable actor, serializable through 
 * a Java JSON parser (Jackson).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait JavaJSONSerializableActor[T] extends SerializableActor[T] {
  val serializer = Serializer.JavaJSON
  def toBinary: Array[Byte] = serializer.toBinary(this)
}

/**
 * Mix in this trait to create a serializable actor, serializable through 
 * a Scala JSON parser (SJSON).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ScalaJSONSerializableActor[T] extends SerializableActor[T] {
  val serializer = Serializer.ScalaJSON
  def toBinary: Array[Byte] = serializer.toBinary(this)
}

/**
 * Life-cycle messages for the Actors
 */
@serializable sealed trait LifeCycleMessage
case class HotSwap(code: Option[Actor.Receive]) extends LifeCycleMessage
case class Restart(reason: Throwable) extends LifeCycleMessage
case class Exit(dead: ActorRef, killer: Throwable) extends LifeCycleMessage
case class Link(child: ActorRef) extends LifeCycleMessage
case class Unlink(child: ActorRef) extends LifeCycleMessage
case class UnlinkAndStop(child: ActorRef) extends LifeCycleMessage
case object Kill extends LifeCycleMessage

// Exceptions for Actors
class ActorStartException private[akka](message: String) extends RuntimeException(message)
class ActorKilledException private[akka](message: String) extends RuntimeException(message)
class ActorInitializationException private[akka](message: String) extends RuntimeException(message)

/**
 * Actor factory module with factory methods for creating various kinds of Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends Logging {
  val TIMEOUT =            config.getInt("akka.actor.timeout", 5000)
  val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  /**
   * A Receive is a convenience type that defines actor message behavior currently modeled as
   * a PartialFunction[Any, Unit].
   */
  type Receive = PartialFunction[Any, Unit]

  private[actor] val actorRefInCreation = new scala.util.DynamicVariable[Option[ActorRef]](None)

  /**
   * Creates a Actor.actorOf out of the Actor with type T.
   * <pre>
   *   import Actor._
   *   val actor = actorOf[MyActor]
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf[MyActor].start
   * </pre>
   */
  def actorOf[T <: Actor: Manifest]: ActorRef = new LocalActorRef(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Creates a Actor.actorOf out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
   * <pre>
   *   import Actor._
   *   val actor = actorOf(new MyActor)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(new MyActor).start
   * </pre>
   */
  def actorOf(factory: => Actor): ActorRef = new LocalActorRef(() => factory)

  /**
   * Use to create an anonymous event-driven actor.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = actor {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def actor(body: Receive): ActorRef =
    actorOf(new Actor() {
      self.lifeCycle = Some(LifeCycle(Permanent))
      def receive: Receive = body
    }).start

  /**
   * Use to create an anonymous transactional event-driven actor.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = transactor {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def transactor(body: Receive): ActorRef =
    actorOf(new Transactor() {
      self.lifeCycle = Some(LifeCycle(Permanent))
      def receive: Receive = body
    }).start

  /**
   * Use to create an anonymous event-driven actor with a 'temporary' life-cycle configuration,
   * which means that if the actor is supervised and dies it will *not* be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = temporaryActor {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def temporaryActor(body: Receive): ActorRef =
    actorOf(new Actor() {
      self.lifeCycle = Some(LifeCycle(Temporary))
      def receive = body
    }).start

  /**
   * Use to create an anonymous event-driven actor with both an init block and a message loop block.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * val a = Actor.init {
   *   ... // init stuff
   * } receive  {
   *   case msg => ... // handle message
   * }
   * </pre>
   *
   */
  def init[A](body: => Unit) = {
    def handler[A](body: => Unit) = new {
      def receive(handler: Receive) =
        actorOf(new Actor() {
          self.lifeCycle = Some(LifeCycle(Permanent))
          body
          def receive = handler
        }).start
    }
    handler(body)
  }

  /**
   * Use to spawn out a block of code in an event-driven actor. Will shut actor down when
   * the block has been executed.
   * <p/>
   * NOTE: If used from within an Actor then has to be qualified with 'Actor.spawn' since
   * there is a method 'spawn[ActorType]' in the Actor trait already.
   * Example:
   * <pre>
   * import Actor._
   *
   * spawn {
   *   ... // do stuff
   * }
   * </pre>
   */
  def spawn(body: => Unit): Unit = {
    case object Spawn
    actorOf(new Actor() {
      def receive = {
        case Spawn => body; self.stop
      }
    }).start ! Spawn
    
  }
}

/**
 * Actor base trait that should be extended by or mixed to create an Actor with the semantics of the 'Actor Model':
 * <a href="http://en.wikipedia.org/wiki/Actor_model">http://en.wikipedia.org/wiki/Actor_model</a>
 * <p/>
 * An actor has a well-defined (non-cyclic) life-cycle.
 * <pre>
 * => NEW (newly created actor) - can't receive messages (yet)
 *     => STARTED (when 'start' is invoked) - can receive messages
 *         => SHUT DOWN (when 'exit' is invoked) - can't do anything
 * </pre>
 *
 * <p/>
 * The Actor's API is available in the 'self' member variable.
 *
 * <p/>
 * Here you find functions like:
 *   - !, !!, !!! and forward
 *   - link, unlink, startLink, spawnLink etc
 *   - makeTransactional, makeRemote etc.
 *   - start, stop
 *   - etc.
 *
 * <p/>
 * Here you also find fields like
 *   - dispatcher = ...
 *   - id = ...
 *   - lifeCycle = ...
 *   - faultHandler = ...
 *   - trapExit = ...
 *   - etc.
 *
 * <p/>
 * This means that to use them you have to prefix them with 'self', like this: <tt>self ! Message</tt>
 *
 * However, for convenience you can import these functions and fields like below, which will allow you do
 * drop the 'self' prefix:
 * <pre>
 * class MyActor extends Actor {
 *   import self._
 *   id = ...
 *   dispatcher = ...
 *   spawnLink[OtherActor]
 *   ...
 * }
 * </pre>
 *
 * <p/>
 * The Actor trait also has a 'log' member field that can be used for logging within the Actor.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Actor extends Logging {
  /**
   * Type alias because traits cannot have companion objects.
   */
  type Receive = Actor.Receive

   /*
    * Option[ActorRef] representation of the 'self' ActorRef reference.
    * <p/>
    * Mainly for internal use, functions as the implicit sender references when invoking
    * one of the message send functions ('!', '!!' and '!!!').
    */
  implicit val optionSelf: Option[ActorRef] = {
    val ref = Actor.actorRefInCreation.value
    Actor.actorRefInCreation.value = None
    if (ref.isEmpty) throw new ActorInitializationException(
       "ActorRef for instance of actor [" + getClass.getName + "] is not in scope." +
       "\n\tYou can not create an instance of an actor explicitly using 'new MyActor'." +
       "\n\tYou have to use one of the factory methods in the 'Actor' object to create a new actor." +
       "\n\tEither use:" +
       "\n\t\t'val actor = Actor.actorOf[MyActor]', or" +
       "\n\t\t'val actor = Actor.actorOf(new MyActor(..))'" +
       "\n\t\t'val actor = Actor.actor { case msg => .. } }'")
    else ref
  }

  /*
   * Some[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * the 'forward' function.
   */
  implicit val someSelf: Some[ActorRef] = optionSelf.asInstanceOf[Some[ActorRef]]

  /**
   * The 'self' field holds the ActorRef for this actor.
   * <p/>
   * Can be used to send messages to itself:
   * <pre>
   * self ! message
   * </pre>
   * Here you also find most of the Actor API.
   * <p/>
   * For example fields like:
   * <pre>
   * self.dispactcher = ...
   * self.trapExit = ...
   * self.faultHandler = ...
   * self.lifeCycle = ...
   * self.sender
   * </pre>
   * <p/>
   * Here you also find methods like:
   * <pre>
   * self.reply(..)
   * self.link(..)
   * self.unlink(..)
   * self.start(..)
   * self.stop(..)
   * </pre>
   */
  val self: ActorRef = {
    val zelf = optionSelf.get
    zelf.id = getClass.getName
    zelf
  }

  /**
   * User overridable callback/setting.
   * <p/>
   * Partial function implementing the actor logic.
   * To be implemented by concrete actor class.
   * <p/>
   * Example code:
   * <pre>
   *   def receive = {
   *     case Ping =&gt;
   *       log.info("got a 'Ping' message")
   *       self.reply("pong")
   *
   *     case OneWay =&gt;
   *       log.info("got a 'OneWay' message")
   *
   *     case unknown =&gt;
   *       log.warning("unknown message [%s], ignoring", unknown)
   * }
   * </pre>
   */
  protected def receive: Receive

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started by invoking 'actor.start'.
   */
  def init {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop' is invoked.
   */
  def shutdown {}

  /**
   * User overridable callback.
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean up of resources before Actor is terminated.
   */
  def preRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   */
  def postRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called during initialization. Can be used to initialize transactional state. Will be invoked within a transaction.
   */
  def initTransactionalState {}

  /**
   * Use <code>reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) = self.reply(message)

  /**
   * Use <code>reply_?(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  def reply_?(message: Any): Boolean = self.reply_?(message)

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def base: Receive = try {
    lifeCycles orElse (self.hotswap getOrElse receive)
  } catch {
    case e: NullPointerException => throw new IllegalStateException(
      "The 'self' ActorRef reference for [" + getClass.getName + "] is NULL, error in the ActorRef initialization process.")
  }

  private val lifeCycles: Receive = {
    case HotSwap(code) =>        self.hotswap = code
    case Restart(reason) =>      self.restart(reason)
    case Exit(dead, reason) =>   self.handleTrapExit(dead, reason)
    case Link(child) =>          self.link(child)
    case Unlink(child) =>        self.unlink(child)
    case UnlinkAndStop(child) => self.unlink(child); child.stop
    case Kill =>                 throw new ActorKilledException("Actor [" + toString + "] was killed by a Kill message")
  }

  override def hashCode: Int = self.hashCode

  override def equals(that: Any): Boolean = self.equals(that)

  override def toString = self.toString
}
