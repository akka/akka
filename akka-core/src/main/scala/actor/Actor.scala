/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging

/*
// FIXME add support for ActorWithNestedReceive
trait ActorWithNestedReceive extends Actor {
  import Actor.actor
  private var nestedReactsProcessors: List[ActorRef] = Nil
  private val processNestedReacts: Receive = {
    case message if !nestedReactsProcessors.isEmpty =>
      val processors = nestedReactsProcessors.reverse
      processors.head forward message
      nestedReactsProcessors = processors.tail.reverse
  }

  protected def react: Receive
  protected def reactAgain(pf: Receive) = nestedReactsProcessors ::= actor(pf)
  protected def receive = processNestedReacts orElse react
}
*/

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

// Life-cycle messages for the Actors
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
      self.start
      self ! Spawn
      def receive = {
        case Spawn => body; self.stop
      }
    })
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
    * For internal use only, functions as the implicit sender references when invoking
    * one of the message send functions (!, !! and !!!).
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
   * For internal use only, functions as the implicit sender references when invoking
   * the forward function.
   */
  implicit val someSelf: Some[ActorRef] = optionSelf.asInstanceOf[Some[ActorRef]]

  /**
   * The 'self' field holds the ActorRef for this actor.
   * <p/>
   * Can be used to send messages to itself:
   * <pre>
   * self ! message
   * </pre>
   */
  val self: ActorRef = optionSelf.get
  self.id = getClass.getName

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
   *       println("got a ping")
   *       self.reply("pong")
   *
   *     case OneWay =&gt;
   *       println("got a oneway")
   *
   *     case _ =&gt;
   *       println("unknown message, ignoring")
   * }
   * </pre>
   */
  protected def receive: Receive

  /**
   * User overridable callback/setting.
   * <p/>
   * Optional callback method that is called during initialization.
   * To be implemented by subclassing actor.
   */
  def init {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  def preRestart(reason: Throwable) {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  def postRestart(reason: Throwable) {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  def initTransactionalState {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  def shutdown {}

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def base: Receive = lifeCycles orElse (self.hotswap getOrElse receive)

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

/**
 * Base class for the different dispatcher types.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed abstract class DispatcherType

/**
 * Module that holds the different dispatcher types.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object DispatcherType {
  case object EventBasedThreadPooledProxyInvokingDispatcher extends DispatcherType
  case object EventBasedSingleThreadDispatcher extends DispatcherType
  case object EventBasedThreadPoolDispatcher extends DispatcherType
  case object ThreadBasedDispatcher extends DispatcherType
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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActorMessageInvoker private[akka] (val actorRef: ActorRef) extends MessageInvoker {
  def invoke(handle: MessageInvocation) = actorRef.invoke(handle)
}
