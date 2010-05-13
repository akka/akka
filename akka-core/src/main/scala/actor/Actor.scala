/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.Transaction.Global._
import se.scalablesolutions.akka.stm.TransactionManagement._
import se.scalablesolutions.akka.stm.TransactionManagement
import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.{RemoteRequestProtocol, RemoteReplyProtocol, ActorRefProtocol}
import se.scalablesolutions.akka.remote.{RemoteNode, RemoteServer, RemoteClient, RemoteProtocolBuilder, RemoteRequestProtocolIdFactory}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.{HashCode, Logging, UUID}

import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.commitbarriers.CountDownCommitBarrier

import jsr166x.{Deque, ConcurrentLinkedDeque}

import java.net.InetSocketAddress
import java.util.concurrent.locks.{Lock, ReentrantLock}
import java.util.{HashSet => JHashSet}

/*
trait ActorWithNestedReceive extends Actor {
  import Actor.actor
  private var nestedReactsProcessors: List[ActorRef] = Nil
  private val processNestedReacts: PartialFunction[Any, Unit] = {
    case message if !nestedReactsProcessors.isEmpty =>
      val processors = nestedReactsProcessors.reverse
      processors.head forward message
      nestedReactsProcessors = processors.tail.reverse
  }
 
  protected def react: PartialFunction[Any, Unit]
  protected def reactAgain(pf: PartialFunction[Any, Unit]) = nestedReactsProcessors ::= actor(pf)
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
  makeTransactionRequired
}

/**
 * Extend this abstract class to create a remote actor.
 * <p/>
 * Equivalent to invoking the <code>makeRemote(..)</code> method in the body of the <code>Actor</code
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class RemoteActor(hostname: String, port: Int) extends Actor {
  makeRemote(hostname, port)
}

// Life-cycle messages for the Actors
@serializable sealed trait LifeCycleMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends LifeCycleMessage
case class Restart(reason: Throwable) extends LifeCycleMessage
case class Exit(dead: ActorRef, killer: Throwable) extends LifeCycleMessage
case class Link(child: ActorRef) extends LifeCycleMessage
case class Unlink(child: ActorRef) extends LifeCycleMessage
case class UnlinkAndStop(child: ActorRef) extends LifeCycleMessage
case object Kill extends LifeCycleMessage

// Exceptions for Actors
class ActorKilledException private[akka](message: String) extends RuntimeException(message)
class ActorInitializationException private[akka](message: String) extends RuntimeException(message)

/**
 * Actor factory module with factory methods for creating various kinds of Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends Logging {
  val TIMEOUT =            config.getInt("akka.actor.timeout", 5000)
  val HOSTNAME =           config.getString("akka.remote.server.hostname", "localhost")
  val PORT =               config.getInt("akka.remote.server.port", 9999)
  val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  private[actor] val actorRefInCreation = new scala.util.DynamicVariable[Option[ActorRef]](None)

  // FIXME remove next release
  object Sender {
    @deprecated("import Actor.Sender.Self is not needed anymore, just use 'actor ! msg'")
    object Self
  }

  /**
   * Creates a Actor.newActor out of the Actor with type T.
   * <pre>
   *   import Actor._
   *   val actor = newActor[MyActor]
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   */
  def newActor[T <: Actor: Manifest]: ActorRef = new LocalActorRef(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Creates a Actor.newActor out of the Actor. Allows you to pass in a factory function 
   * that creates the Actor. Please note that this function can be invoked multiple 
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
   * <pre>
   *   import Actor._
   *   val actor = newActor(() => new MyActor)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   */
  def newActor(factory: () => Actor): ActorRef = new LocalActorRef(factory)

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
  def actor(body: PartialFunction[Any, Unit]): ActorRef =
    newActor(() => new Actor() {
      lifeCycle = Some(LifeCycle(Permanent))
      def receive: PartialFunction[Any, Unit] = body
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
  def transactor(body: PartialFunction[Any, Unit]): ActorRef =
    newActor(() => new Transactor() {
      lifeCycle = Some(LifeCycle(Permanent))
      def receive: PartialFunction[Any, Unit] = body
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
  def temporaryActor(body: PartialFunction[Any, Unit]): ActorRef =
    newActor(() => new Actor() {
      lifeCycle = Some(LifeCycle(Temporary))
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
      def receive(handler: PartialFunction[Any, Unit]) =
        newActor(() => new Actor() {
          lifeCycle = Some(LifeCycle(Permanent))
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
    newActor(() => new Actor() {
      self ! Spawn
      def receive = {
        case Spawn => body; stop
      }
    }).start
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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Actor extends Logging {
 
   /** 
    * For internal use only, functions as the implicit sender references when invoking 
    * one of the message send functions (!, !!, !!! and forward).
    */
  protected implicit val _selfSenderRef: Some[ActorRef] = { 
    val ref = Actor.actorRefInCreation.value
    Actor.actorRefInCreation.value = None
    if (ref.isEmpty) throw new ActorInitializationException(
       "ActorRef for instance of actor [" + getClass.getName + "] is not in scope." + 
       "\n\tYou can not create an instance of an actor explicitly using 'new MyActor'." + 
       "\n\tYou have to use one of the factory methods in the 'Actor' object to create a new actor." + 
       "\n\tEither use:" + 
       "\n\t\t'val actor = Actor.newActor[MyActor]', or" + 
       "\n\t\t'val actor = Actor.newActor(() => new MyActor(..))'")
    else ref.asInstanceOf[Some[ActorRef]]
  }

  /**
   * The 'self' field holds the ActorRef for this actor.
   * <p/>
   * Can be used to send messages to itself:
   * <pre>
   * self ! message
   * </pre>
   */
  val self: ActorRef = _selfSenderRef.get

  /**
   * The default dispatcher is the <tt>Dispatchers.globalExecutorBasedEventDrivenDispatcher</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>se.scalablesolutions.akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  private[akka] var _messageDispatcher: MessageDispatcher = Dispatchers.globalExecutorBasedEventDrivenDispatcher

  /**
   * Holds the hot swapped partial function.
   */
  private var _hotswap: Option[PartialFunction[Any, Unit]] = None // FIXME: _hotswap should be a stack

  // ========================================
  // ==== CALLBACKS FOR USER TO OVERRIDE ====
  // ========================================

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

  /**
   * User overridable callback/setting.
   * <p/>
   * Partial function implementing the actor logic.
   * To be implemented by subclassing actor.
   * <p/>
   * Example code:
   * <pre>
   *   def receive = {
   *     case Ping =&gt;
   *       println("got a ping")
   *       reply("pong")
   *
   *     case OneWay =&gt;
   *       println("got a oneway")
   *
   *     case _ =&gt;
   *       println("unknown message, ignoring")
   * }
   * </pre>
   */
  protected def receive: PartialFunction[Any, Unit]

  // ==================
  // ==== USER API ====
  // ==================

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!', '!!' and '!!!'.
   */
  def forward(message: Any)(implicit sender: Some[ActorRef]) = self.forward(message)(sender)

  /**
   * User overridable callback/setting.
   * <p/>
   * Identifier for actor, does not have to be a unique one.
   * Default is the class name.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor, identifier for remote actor in RemoteServer etc.
   * But also as the identifier for persistence, which means that you can
   * use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  protected[akka] var id: String = this.getClass.getName

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  @volatile var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback/setting.
   * <p/>
   * Set trapExit to the list of exception classes that the actor should be able to trap
   * from the actor it is supervising. When the supervising actor throws these exceptions
   * then they will trigger a restart.
   * <p/>
   * <pre>
   * // trap no exceptions
   * trapExit = Nil
   *
   * // trap all exceptions
   * trapExit = List(classOf[Throwable])
   *
   * // trap specific exceptions only
   * trapExit = List(classOf[MyApplicationException], classOf[MyApplicationError])
   * </pre>
   */
  protected[akka] var trapExit: List[Class[_ <: Throwable]] = Nil

  /**
   * User overridable callback/setting.
   * <p/>
   * If 'trapExit' is set for the actor to act as supervisor, then a faultHandler must be defined.
   * Can be one of:
   * <pre/>
   *  faultHandler = Some(AllForOneStrategy(maxNrOfRetries, withinTimeRange))
   *
   *  faultHandler = Some(OneForOneStrategy(maxNrOfRetries, withinTimeRange))
   * </pre>
   */
  protected[akka] var faultHandler: Option[FaultHandlingStrategy] = None

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the life-cycle for a supervised actor.
   */
  @volatile var lifeCycle: Option[LifeCycle] = None

  /**
   * Use <code>reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to
   */
  protected[this] def reply(message: Any) = if(!reply_?(message)) throw new IllegalStateException(
    "\n\tNo sender in scope, can't reply. " +
    "\n\tYou have probably used the '!' method to either; " +
    "\n\t\t1. Send a message to a remote actor which does not have a contact address." +
    "\n\t\t2. Send a message from an instance that is *not* an actor" +
    "\n\t\t3. Send a message to an Active Object annotated with the '@oneway' annotation? " +
    "\n\tIf so, switch to '!!' (or remove '@oneway') which passes on an implicit future" +
    "\n\tthat will be bound by the argument passed to 'reply'." +
    "\n\tAlternatively, you can use setReplyToAddress to make sure the actor can be contacted over the network.")

  /**
   * Use <code>reply_?(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  protected[this] def reply_?(message: Any): Boolean = self.replyTo match {
    case Some(Left(actor)) =>
      actor ! message
      true
    case Some(Right(future: Future[Any])) =>
      future completeWithResult message
      true
    case _ =>
      false
  }
  
  /**
   * Starts the actor.
   */
  def start = self.startOnCreation = true

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit = self.stop

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop = self.stop

  /**
   * Returns the uuid for the actor.
   */
  def uuid = self.uuid

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   * <p/>
   * The default dispatcher is the <tt>Dispatchers.globalExecutorBasedEventDrivenDispatcher</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>se.scalablesolutions.akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  def dispatcher_=(md: MessageDispatcher): Unit = 
    if (!self.isRunning) _messageDispatcher = md
    else throw new IllegalArgumentException(
      "Can not swap dispatcher for " + toString + " after it has been started")
    
  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = _messageDispatcher

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking:
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactionRequired = self.makeTransactionRequired

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit = makeRemote(new InetSocketAddress(hostname, port))

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit = self.makeRemote(address)

  /**
   * Set the contact address for this actor. This is used for replying to messages sent 
   * asynchronously when no reply channel exists.
   */
  def setReplyToAddress(hostname: String, port: Int): Unit = self.setReplyToAddress(new InetSocketAddress(hostname, port))

  /**
   * Set the contact address for this actor. This is used for replying to messages sent 
   * asynchronously when no reply channel exists.
   */
  def setReplyToAddress(address: InetSocketAddress): Unit = self.setReplyToAddress(address)

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def link(actorRef: ActorRef) = self.link(actorRef)

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def unlink(actorRef: ActorRef) = self.unlink(actorRef)

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def startLink(actorRef: ActorRef) = self.startLink(actorRef)

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int) = 
    self.startLinkRemote(actorRef, hostname, port)

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawn[T <: Actor : Manifest]: ActorRef = self.spawn[T]

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = 
    self.spawnRemote[T](hostname, port)

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnLink[T <: Actor: Manifest]: ActorRef = self.spawnLink[T]

  /**
   * User overridable callback/setting.
   * <p/>
   * Set to true if messages should have REQUIRES_NEW semantics, e.g. a new transaction should
   * start if there is no one running, else it joins the existing transaction.
   */
  protected[this] def isTransactor_=(flag: Boolean) = self.isTransactor = flag

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def base: PartialFunction[Any, Unit] = lifeCycles orElse (_hotswap getOrElse receive)

  private val lifeCycles: PartialFunction[Any, Unit] = {
    case HotSwap(code) =>        _hotswap = code
    case Restart(reason) =>      self.restart(reason)
    case Exit(dead, reason) =>   self.handleTrapExit(dead, reason)
    case Unlink(child) =>        self.unlink(child)
    case UnlinkAndStop(child) => self.unlink(child); child.stop
    case Kill =>                 throw new ActorKilledException("Actor [" + toString + "] was killed by a Kill message")
  }
  
  override def hashCode(): Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that != null &&
    that.isInstanceOf[Actor] &&
    that.asInstanceOf[Actor].uuid == uuid
  }

  override def toString = "Actor[" + id + ":" + uuid + "]"
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
