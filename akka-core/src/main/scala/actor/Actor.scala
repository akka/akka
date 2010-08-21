/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.Helpers.{narrow, narrowSilently}
import se.scalablesolutions.akka.util.{Logging, Duration}
import se.scalablesolutions.akka.AkkaException

import com.google.protobuf.Message

import java.util.concurrent.TimeUnit
import java.net.InetSocketAddress

import scala.reflect.BeanProperty

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
abstract class RemoteActor(address: InetSocketAddress) extends Actor {
  def this(hostname: String, port: Int) = this(new InetSocketAddress(hostname, port))
  self.makeRemote(address)
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

case object ReceiveTimeout extends LifeCycleMessage

case class MaximumNumberOfRestartsWithinTimeRangeReached(
  @BeanProperty val victim: ActorRef,
  @BeanProperty val maxNrOfRetries: Int,
  @BeanProperty val withinTimeRange: Int,
  @BeanProperty val lastExceptionCausingRestart: Throwable) extends LifeCycleMessage

// Exceptions for Actors
class ActorStartException private[akka](message: String) extends AkkaException(message)
class IllegalActorStateException private[akka](message: String) extends AkkaException(message)
class ActorKilledException private[akka](message: String) extends AkkaException(message)
class ActorInitializationException private[akka](message: String) extends AkkaException(message)
class ActorTimeoutException private[akka](message: String) extends AkkaException(message)

/**
 * Actor factory module with factory methods for creating various kinds of Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends Logging {
  val TIMEOUT = Duration(config.getInt("akka.actor.timeout", 5), TIME_UNIT).toMillis
  val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  /**
   * A Receive is a convenience type that defines actor message behavior currently modeled as
   * a PartialFunction[Any, Unit].
   */
  type Receive = PartialFunction[Any, Unit]

  private[actor] val actorRefInCreation = new scala.util.DynamicVariable[Option[ActorRef]](None)

  /**
   * Creates an ActorRef out of the Actor with type T.
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
  def actorOf[T <: Actor : Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
     * Creates an ActorRef out of the Actor with type T.
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
    def actorOf(clazz: Class[_ <: Actor]): ActorRef = new LocalActorRef(clazz)


  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory function
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
   * val a = actor  {
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
   * val a = transactor  {
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
   * val a = temporaryActor  {
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
   * val a = Actor.init  {
   *   ... // init stuff
   * } receive   {
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
   * spawn  {
   *   ... // do stuff
   * }
   * </pre>
   */
  def spawn(body: => Unit): Unit = {
    case object Spawn
    actorOf(new Actor() {
      def receive = {
        case Spawn => try { body } finally { self.stop }
      }
    }).start ! Spawn
  }

  /**
   * Implicitly converts the given Option[Any] to a AnyOptionAsTypedOption which offers the method <code>as[T]</code>
   * to convert an Option[Any] to an Option[T].
   */
  implicit def toAnyOptionAsTypedOption(anyOption: Option[Any]) = new AnyOptionAsTypedOption(anyOption)
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
 * class MyActor extends Actor  {
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
   * Some[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * the 'forward' function.
   */
  @transient implicit val someSelf: Some[ActorRef] = {
    val optRef = Actor.actorRefInCreation.value
    if (optRef.isEmpty) throw new ActorInitializationException(
      "ActorRef for instance of actor [" + getClass.getName + "] is not in scope." +
      "\n\tYou can not create an instance of an actor explicitly using 'new MyActor'." +
      "\n\tYou have to use one of the factory methods in the 'Actor' object to create a new actor." +
      "\n\tEither use:" +
      "\n\t\t'val actor = Actor.actorOf[MyActor]', or" +
      "\n\t\t'val actor = Actor.actorOf(new MyActor(..))', or" +
      "\n\t\t'val actor = Actor.actor { case msg => .. } }'")

     val ref = optRef.asInstanceOf[Some[ActorRef]].get
     ref.id = getClass.getName //FIXME: Is this needed?
     optRef.asInstanceOf[Some[ActorRef]]
  }

   /*
   * Option[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * one of the message send functions ('!', '!!' and '!!!').
   */
  implicit def optionSelf: Option[ActorRef] = someSelf

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
  @transient val self: ScalaActorRef = someSelf.get

  /**
   * User overridable callback/setting.
   * <p/>
   * Partial function implementing the actor logic.
   * To be implemented by concrete actor class.
   * <p/>
   * Example code:
   * <pre>
   *   def receive =  {
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
   * Is the actor able to handle the message passed in as arguments?
   */
  def isDefinedAt(message: Any): Boolean = processingBehavior.isDefinedAt(message)

  /** One of the fundamental methods of the ActorsModel
   * Actor assumes a new behavior,
   * None reverts the current behavior to the original behavior
   */
  def become(behavior: Option[Receive]) {
    self.hotswap = behavior
    self.checkReceiveTimeout // FIXME : how to reschedule receivetimeout on hotswap?
  }

  /** Akka Java API
   * One of the fundamental methods of the ActorsModel
   * Actor assumes a new behavior,
   * null reverts the current behavior to the original behavior
   */
  def become(behavior: Receive): Unit = become(Option(behavior))

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def apply(msg: Any) = processingBehavior(msg)

  private lazy val processingBehavior: Receive = {
    lazy val defaultBehavior = receive
    val actorBehavior: Receive = {
      case HotSwap(code)                 => become(code)
      case Exit(dead, reason)            => self.handleTrapExit(dead, reason)
      case Link(child)                   => self.link(child)
      case Unlink(child)                 => self.unlink(child)
      case UnlinkAndStop(child)          => self.unlink(child); child.stop
      case Restart(reason)               => throw reason
      case msg if self.hotswap.isDefined &&
                  self.hotswap.get.isDefinedAt(msg) => self.hotswap.get.apply(msg)
      case msg if self.hotswap.isEmpty   &&
                  defaultBehavior.isDefinedAt(msg)  => defaultBehavior.apply(msg)
    }
    actorBehavior
  }
}

private[actor] class AnyOptionAsTypedOption(anyOption: Option[Any]) {

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will throw a ClassCastException
   * if the actual type is not assignable from the given one.
   */
  def as[T]: Option[T] = narrow[T](anyOption)

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will swallow a possible
   * ClassCastException and return None in that case.
   */
  def asSilently[T: Manifest]: Option[T] = narrowSilently[T](anyOption)
}
