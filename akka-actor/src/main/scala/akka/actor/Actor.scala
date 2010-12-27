/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.dispatch._
import akka.config.Config._
import akka.config.Supervision._
import akka.util.Helpers.{narrow, narrowSilently}
import akka.AkkaException

import java.util.concurrent.TimeUnit
import java.net.InetSocketAddress

import scala.reflect.BeanProperty
import akka.util. {ReflectiveAccess, Logging, Duration}
import akka.japi.Procedure

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

case class HotSwap(code: ActorRef => Actor.Receive, discardOld: Boolean = true) extends LifeCycleMessage {
  /**
   * Java API
   */
  def this(code: akka.japi.Function[ActorRef,Procedure[Any]], discardOld: Boolean) =
    this( (self: ActorRef) => {
      val behavior = code(self)
      val result: Actor.Receive = { case msg => behavior(msg) }
      result
    }, discardOld)

  /**
   *  Java API with default non-stacking behavior
   */
  def this(code: akka.japi.Function[ActorRef,Procedure[Any]]) = this(code, true)
}

case object RevertHotSwap extends LifeCycleMessage

case class Restart(reason: Throwable) extends LifeCycleMessage

case class Exit(dead: ActorRef, killer: Throwable) extends LifeCycleMessage

case class Link(child: ActorRef) extends LifeCycleMessage

case class Unlink(child: ActorRef) extends LifeCycleMessage

case class UnlinkAndStop(child: ActorRef) extends LifeCycleMessage

case object ReceiveTimeout extends LifeCycleMessage

case class MaximumNumberOfRestartsWithinTimeRangeReached(
  @BeanProperty val victim: ActorRef,
  @BeanProperty val maxNrOfRetries: Option[Int],
  @BeanProperty val withinTimeRange: Option[Int],
  @BeanProperty val lastExceptionCausingRestart: Throwable) extends LifeCycleMessage

// Exceptions for Actors
class ActorStartException private[akka](message: String) extends AkkaException(message)
class IllegalActorStateException private[akka](message: String) extends AkkaException(message)
class ActorKilledException private[akka](message: String) extends AkkaException(message)
class ActorInitializationException private[akka](message: String) extends AkkaException(message)
class ActorTimeoutException private[akka](message: String) extends AkkaException(message)

/**
 *    This message is thrown by default when an Actors behavior doesn't match a message
 */
case class UnhandledMessageException(msg: Any, ref: ActorRef) extends Exception {
  override def getMessage() = "Actor %s does not handle [%s]".format(ref,msg)
  override def fillInStackTrace() = this //Don't waste cycles generating stack trace
}

/**
 * Actor factory module with factory methods for creating various kinds of Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends Logging {

  /**
   * Add shutdown cleanups
   */
  private[akka] lazy val shutdownHook = {
    val hook = new Runnable {
      override def run {
        // Shutdown HawtDispatch GlobalQueue
        log.slf4j.info("Shutting down Hawt Dispatch global queue")
        org.fusesource.hawtdispatch.ScalaDispatch.globalQueue.asInstanceOf[org.fusesource.hawtdispatch.internal.GlobalDispatchQueue].shutdown

        // Clear Thread.subclassAudits
        log.slf4j.info("Clearing subclass audits")
        val tf = classOf[java.lang.Thread].getDeclaredField("subclassAudits")
        tf.setAccessible(true)
        val subclassAudits = tf.get(null).asInstanceOf[java.util.Map[_,_]]
        subclassAudits.synchronized {subclassAudits.clear}
      }
    }
    Runtime.getRuntime.addShutdownHook(new Thread(hook))
    hook
  }

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
  def actorOf(clazz: Class[_ <: Actor]): ActorRef = new LocalActorRef(() => {
    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    createInstance[Actor](clazz.asInstanceOf[Class[_]], noParams, noArgs).getOrElse(
      throw new ActorInitializationException(
        "Could not instantiate Actor" +
        "\nMake sure Actor is NOT defined inside a class/trait," +
        "\nif so put it outside the class/trait, f.e. in a companion object," +
        "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'."))
  })

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
  def spawn(body: => Unit)(implicit dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher): Unit = {
    case object Spawn
    actorOf(new Actor() {
      self.dispatcher = dispatcher
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
 *   - makeRemote etc.
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
   *       log.slf4j.info("got a 'Ping' message")
   *       self.reply("pong")
   *
   *     case OneWay =&gt;
   *       log.slf4j.info("got a 'OneWay' message")
   *
   *     case unknown =&gt;
   *       log.slf4j.warn("unknown message [{}], ignoring", unknown)
   * }
   * </pre>
   */
  protected def receive: Receive

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started by invoking 'actor.start'.
   */
  def preStart {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop' is invoked.
   */
  def postStop {}

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
   * Is called when a message isn't handled by the current behavior of the actor
   * by default it throws an UnhandledMessageException
   */
  def unhandled(msg: Any){
    throw new UnhandledMessageException(msg,self)
  }

  /**
   * Is the actor able to handle the message passed in as arguments?
   */
  def isDefinedAt(message: Any): Boolean = processingBehavior.isDefinedAt(message)

  /**
   * Changes tha Actor's behavior to become the new 'Receive' (PartialFunction[Any, Unit]) handler.
   * Puts the behavior on top of the hotswap stack.
   * If "discardOld" is true, an unbecome will be issued prior to pushing the new behavior to the stack
   */
  def become(behavior: Receive, discardOld: Boolean = true) {
    if (discardOld)
      unbecome

    self.hotswap = self.hotswap.push(behavior)
  }

  /**
   * Reverts the Actor behavior to the previous one in the hotswap stack.
   */
  def unbecome: Unit = if (!self.hotswap.isEmpty) self.hotswap = self.hotswap.pop

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def apply(msg: Any) = fullBehavior(msg)

  /*Processingbehavior and fullBehavior are duplicates so make sure changes are done to both */
  private lazy val processingBehavior: Receive = {
    lazy val defaultBehavior = receive
    val actorBehavior: Receive = {
      case HotSwap(code,discardOld)                  => become(code(self),discardOld)
      case RevertHotSwap                             => unbecome
      case Exit(dead, reason)                        => self.handleTrapExit(dead, reason)
      case Link(child)                               => self.link(child)
      case Unlink(child)                             => self.unlink(child)
      case UnlinkAndStop(child)                      => self.unlink(child); child.stop
      case Restart(reason)                           => throw reason
      case msg if !self.hotswap.isEmpty &&
                  self.hotswap.head.isDefinedAt(msg) => self.hotswap.head.apply(msg)
      case msg if self.hotswap.isEmpty   &&
                  defaultBehavior.isDefinedAt(msg)   => defaultBehavior.apply(msg)
    }
    actorBehavior
  }

  private lazy val fullBehavior: Receive = {
    lazy val defaultBehavior = receive
    val actorBehavior: Receive = {
      case HotSwap(code, discardOld)                 => become(code(self), discardOld)
      case RevertHotSwap                             => unbecome
      case Exit(dead, reason)                        => self.handleTrapExit(dead, reason)
      case Link(child)                               => self.link(child)
      case Unlink(child)                             => self.unlink(child)
      case UnlinkAndStop(child)                      => self.unlink(child); child.stop
      case Restart(reason)                           => throw reason
      case msg if !self.hotswap.isEmpty &&
                  self.hotswap.head.isDefinedAt(msg) => self.hotswap.head.apply(msg)
      case msg if self.hotswap.isEmpty   &&
                  defaultBehavior.isDefinedAt(msg)   => defaultBehavior.apply(msg)
      case unknown                                   => unhandled(unknown) //This is the only line that differs from processingbehavior
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

/**
 * Marker interface for proxyable actors (such as typed actor).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Proxyable {
  private[actor] def swapProxiedActor(newInstance: Actor)
}

/**
 * Represents the different Actor types.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed trait ActorType
object ActorType {
  case object ScalaActor extends ActorType
  case object TypedActor extends ActorType
}
