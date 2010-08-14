/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.stm.global._
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.config.ScalaConfig._

import java.net.InetSocketAddress

import scala.reflect.BeanProperty

/**
 * Subclass this abstract class to create a MDB-style untyped actor.
 * <p/>
 * This class is meant to be used from Java.
 * <p/>
 * Here is an example on how to create and use an UntypedActor:
 * <pre>
 *  public class SampleUntypedActor extends UntypedActor {
 *    public void onReceive(Object message) throws Exception {
 *      if (message instanceof String) {
 *        String msg = (String)message;
 *
 *        if (msg.equals("UseReply")) {
 *          // Reply to original sender of message using the 'replyUnsafe' method
 *          getContext().replyUnsafe(msg + ":" + getContext().getUuid());
 *
 *        } else if (msg.equals("UseSender") && getContext().getSender().isDefined()) {
 *          // Reply to original sender of message using the sender reference
 *          // also passing along my own refererence (the context)
 *          getContext().getSender().get().sendOneWay(msg, context);
 *
 *        } else if (msg.equals("UseSenderFuture") && getContext().getSenderFuture().isDefined()) {
 *          // Reply to original sender of message using the sender future reference
 *          getContext().getSenderFuture().get().completeWithResult(msg);
 *
 *        } else if (msg.equals("SendToSelf")) {
 *          // Send message to the actor itself recursively
 *          getContext().sendOneWay(msg)
 *
 *        } else if (msg.equals("ForwardMessage")) {
 *          // Retreive an actor from the ActorRegistry by ID and get an ActorRef back
 *          ActorRef actorRef = ActorRegistry.actorsFor("some-actor-id").head();
 *          // Wrap the ActorRef in an UntypedActorRef and forward the message to this actor
 *          UntypedActorRef.wrap(actorRef).forward(msg, context);
 *
 *        } else throw new IllegalArgumentException("Unknown message: " + message);
 *      } else throw new IllegalArgumentException("Unknown message: " + message);
 *    }
 *
 *    public static void main(String[] args) {
 *      UntypedActorRef actor = UntypedActor.actorOf(SampleUntypedActor.class);
 *      actor.start();
 *      actor.sendOneWay("SendToSelf");
 *      actor.stop();
 *    }
 *  }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class UntypedActor extends Actor {
  @BeanProperty val context = UntypedActorRef.wrap(self)

  final protected def receive = {
    case msg => onReceive(msg)
  }

  @throws(classOf[Exception])
  def onReceive(message: Any): Unit
}

/**
 * Implements the Transactor abstraction. E.g. a transactional UntypedActor.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class UntypedTransactor extends UntypedActor {
  self.makeTransactionRequired
}

/**
 * Factory closure for an UntypedActor, to be used with 'UntypedActor.actorOf(factory)'.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait UntypedActorFactory {
 def create: UntypedActor
}

/**
 * Extend this abstract class to create a remote UntypedActor.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class RemoteUntypedActor(address: InetSocketAddress) extends UntypedActor {
  def this(hostname: String, port: Int) = this(new InetSocketAddress(hostname, port))
  self.makeRemote(address)
}

/**
 * Factory object for creating and managing 'UntypedActor's. Meant to be used from Java.
 * <p/>
 * Example on how to create an actor:
 * <pre>
 *   ActorRef actor = UntypedActor.actorOf(MyUntypedActor.class);
 *   actor.start();
 *   actor.sendOneWay(message, context)
 *   actor.stop();
 * </pre>
 * You can create and start the actor in one statement like this:
 * <pre>
 *   ActorRef actor = UntypedActor.actorOf(MyUntypedActor.class).start();
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object UntypedActor {

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in the class for the Actor.
   * <p/>
   * Example in Java:
   * <pre>
   *   ActorRef actor = UntypedActor.actorOf(MyUntypedActor.class);
   *   actor.start();
   *   actor.sendOneWay(message, context);
   *   actor.stop();
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   ActorRef actor = UntypedActor.actorOf(MyUntypedActor.class).start();
   * </pre>
   */
  def actorOf(clazz: Class[_]): UntypedActorRef = {
    if (!clazz.isInstanceOf[Class[_ <: UntypedActor]]) throw new IllegalArgumentException(
      "Class [" + clazz.getName + "] passed into the 'actorOf' factory method needs to be assignable from 'UntypedActor'")
    UntypedActorRef.wrap(new LocalActorRef(() => clazz.newInstance.asInstanceOf[Actor]))
  }

  /**
   * NOTE: Use this convenience method with care, do NOT make it possible to get a reference to the
   * UntypedActor instance directly, but only through its 'UntypedActorRef' wrapper reference.
   * <p/>
   * Creates an UntypedActorRef out of the Actor. Allows you to pass in the instance for the UntypedActor. 
   * Only use this method when you need to pass in constructor arguments into the 'UntypedActor'.
   * <p/>
   * You use it by implementing the UntypedActorFactory interface.
   * Example in Java:
   * <pre>
   *   UntypedActorRef actor = UntypedActor.actorOf(new UntypedActorFactory() {
   *     public UntypedActor create() { 
   *       return new MyUntypedActor("service:name", 5); 
   *     }
   *   });
   *   actor.start();
   *   actor.sendOneWay(message, context);
   *   actor.stop();
   * </pre>
   */
  def actorOf(factory: UntypedActorFactory) = UntypedActorRef.wrap(new LocalActorRef(() => factory.create))  
}

/**
 * Use this class if you need to wrap an 'ActorRef' in the more Java-friendly 'UntypedActorRef'.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object UntypedActorRef {
  def wrap(actorRef: ActorRef) = new UntypedActorRef(actorRef)
}

/**
 * A Java-friendly wrapper class around the 'ActorRef'.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class UntypedActorRef(val actorRef: ActorRef) {

  /**
   * Returns the uuid for the actor.
   */
  def getUuid(): String = actorRef.uuid

  /**
   * Identifier for actor, does not have to be a unique one. Default is the 'uuid'.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor(id), identifier for remote
   * actor in RemoteServer etc.But also as the identifier for persistence, which means
   * that you can use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  def setId(id: String) = actorRef.id = id
  def getId(): String = actorRef.id

  /**
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  def setTimeout(timeout: Long) = actorRef.timeout = timeout
  def getTimeout(): Long = actorRef.timeout

  /**
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  def setReceiveTimeout(timeout: Long) = actorRef.receiveTimeout = Some(timeout)
  def getReceiveTimeout(): Option[Long] = actorRef.receiveTimeout

  /**
   * Set 'trapExit' to the list of exception classes that the actor should be able to trap
   * from the actor it is supervising. When the supervising actor throws these exceptions
   * then they will trigger a restart.
   * <p/>
   *
   * Trap all exceptions:
   * <pre>
   * getContext().setTrapExit(new Class[]{Throwable.class});
   * </pre>
   *
   * Trap specific exceptions only:
   * <pre>
   * getContext().setTrapExit(new Class[]{MyApplicationException.class, MyApplicationError.class});
   * </pre>
   */
  def setTrapExit(exceptions: Array[Class[_ <: Throwable]]) = actorRef.trapExit = exceptions.toList
  def getTrapExit(): Array[Class[_ <: Throwable]] = actorRef.trapExit.toArray

  /**
   * If 'trapExit' is set for the actor to act as supervisor, then a 'faultHandler' must be defined.
   * <p/>
   * Can be one of:
   * <pre>
   *  getContext().setFaultHandler(new AllForOneStrategy(maxNrOfRetries, withinTimeRange));
   * </pre>
   * Or:
   * <pre>
   *  getContext().setFaultHandler(new OneForOneStrategy(maxNrOfRetries, withinTimeRange));
   * </pre>
   */
  def setFaultHandler(handler: FaultHandlingStrategy) = actorRef.faultHandler = Some(handler)
  def getFaultHandler(): Option[FaultHandlingStrategy] = actorRef.faultHandler

  /**
   * Defines the life-cycle for a supervised actor.
   */
  def setLifeCycle(lifeCycle: LifeCycle) = actorRef.lifeCycle = Some(lifeCycle)
  def getLifeCycle(): Option[LifeCycle] = actorRef.lifeCycle

  /**
   * The default dispatcher is the <tt>Dispatchers.globalExecutorBasedEventDrivenDispatcher();</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>se.scalablesolutions.akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  def setDispatcher(dispatcher: MessageDispatcher) = actorRef.dispatcher = dispatcher
  def getDispatcher(): MessageDispatcher = actorRef.dispatcher

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def getSender(): Option[UntypedActorRef] = actorRef.sender match {
    case Some(s) => Some(UntypedActorRef.wrap(s))
    case None    => None
  }

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with 'sendRequestReply' or 'sendRequestReplyFuture', else None.
   */
  def getSenderFuture(): Option[CompletableFuture[Any]] = actorRef.senderFuture

  /**
   * Starts up the actor and its message queue.
   */
  def start(): UntypedActorRef = UntypedActorRef.wrap(actorRef.start)

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit() = stop()

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop(): Unit = actorRef.stop()

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   * <pre>
   *   actor.sendOneWay(message);
   * </pre>
   * <p/>
   */
  def sendOneWay(message: AnyRef) = actorRef.!(message)(None)

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   * Allows you to pass along the sender of the messag.
   * <p/>
   * <pre>
   *   actor.sendOneWay(message, context);
   * </pre>
   * <p/>
   */
  def sendOneWay(message: AnyRef, sender: UntypedActorRef) =
    if (sender eq null) actorRef.!(message)(None)
    else actorRef.!(message)(Some(sender.actorRef))

  /**
   * Sends a message asynchronously and waits on a future for a reply message under the hood. The timeout is taken from
   * the default timeout in the Actor.
   * <p/>
   * It waits on the reply either until it receives it or until the timeout expires
   * (which will throw an ActorTimeoutException). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReply</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReply(message: AnyRef): AnyRef =
    actorRef.!!(message)(None).getOrElse(throw new ActorTimeoutException(
      "Message [" + message +
      "]\n\tsent to [" + actorRef.actorClassName +
      "]\n\twith timeout [" + actorRef.timeout +
      "]\n\ttimed out."))
    .asInstanceOf[AnyRef]

  /**
   * Sends a message asynchronously and waits on a future for a reply message under the hood. The timeout is taken from
   * the default timeout in the Actor.
   * <p/>
   * It waits on the reply either until it receives it or until the timeout expires
   * (which will throw an ActorTimeoutException). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReply</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReply(message: AnyRef, sender: UntypedActorRef): AnyRef = {
    val result = if (sender eq null) actorRef.!!(message)(None)
                 else actorRef.!!(message)(Some(sender.actorRef))
    result.getOrElse(throw new ActorTimeoutException(
      "Message [" + message +
      "]\n\tsent to [" + actorRef.actorClassName +
      "]\n\tfrom [" + sender.actorRef.actorClassName +
      "]\n\twith timeout [" + actorRef.timeout +
      "]\n\ttimed out."))
    .asInstanceOf[AnyRef]
  }

  /**
   * Sends a message asynchronously and waits on a future for a reply message under the hood.
   * <p/>
   * It waits on the reply either until it receives it or until the timeout expires
   * (which will throw an ActorTimeoutException). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReply</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReply(message: AnyRef, timeout: Long): AnyRef =
    actorRef.!!(message, timeout)(None).getOrElse(throw new ActorTimeoutException(
      "Message [" + message +
      "]\n\tsent to [" + actorRef.actorClassName +
      "]\n\twith timeout [" + timeout +
      "]\n\ttimed out."))
    .asInstanceOf[AnyRef]

  /**
   * Sends a message asynchronously and waits on a future for a reply message under the hood.
   * <p/>
   * It waits on the reply either until it receives it or until the timeout expires
   * (which will throw an ActorTimeoutException). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReply</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReply(message: AnyRef, timeout: Long, sender: UntypedActorRef): AnyRef = {
    val result = if (sender eq null) actorRef.!!(message, timeout)(None)
                 else actorRef.!!(message)(Some(sender.actorRef))
    result.getOrElse(throw new ActorTimeoutException(
      "Message [" + message +
      "]\n\tsent to [" + actorRef.actorClassName +
      "]\n\tfrom [" + sender.actorRef.actorClassName +
      "]\n\twith timeout [" + timeout +
      "]\n\ttimed out."))
    .asInstanceOf[AnyRef]
  }

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message. The timeout is taken from
   * the default timeout in the Actor.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReplyFuture</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReplyFuture(message: AnyRef): Future[_] = actorRef.!!!(message)(None)

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message. The timeout is taken from
   * the default timeout in the Actor.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReplyFuture</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReplyFuture(message: AnyRef, sender: UntypedActorRef): Future[_] =
    if (sender eq null) actorRef.!!!(message)(None)
    else actorRef.!!!(message)(Some(sender.actorRef))

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReplyFuture</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReplyFuture(message: AnyRef, timeout: Long): Future[_] = actorRef.!!!(message, timeout)(None)

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReplyFuture</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReplyFuture(message: AnyRef, timeout: Long, sender: UntypedActorRef): Future[_] =
    if (sender eq null) actorRef.!!!(message, timeout)(None)
    else actorRef.!!!(message)(Some(sender.actorRef))

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with 'sendOneWay', 'sendRequestReply' and 'sendRequestReplyFuture'.
   */
  def forward(message: AnyRef, sender: UntypedActorRef): Unit =
    if (sender eq null) throw new IllegalArgumentException("The 'sender' argument to 'forward' can't be null")
    else actorRef.forward(message)(Some(sender.actorRef))

  /**
   * Use <code>getContext().replyUnsafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def replyUnsafe(message: AnyRef): Unit = actorRef.reply(message)

    /**
   * Use <code>getContext().replySafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  def replySafe(message: AnyRef): Boolean = actorRef.reply_?(message)

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def getActorClass(): Class[_ <: Actor] = actorRef.actorClass

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  def getActorClassName(): String = actorRef.actorClassName

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit = actorRef.makeRemote(hostname, port)

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit = actorRef.makeRemote(address)

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   */
  def makeTransactionRequired(): Unit = actorRef.makeTransactionRequired

  /**
   * Sets the transaction configuration for this actor. Needs to be invoked before the actor is started.
   */
  def setTransactionConfig(config: TransactionConfig): Unit = actorRef.transactionConfig = config

  /**
   * Get the transaction configuration for this actor.
   */
  def getTransactionConfig(): TransactionConfig = actorRef.transactionConfig

  /**
   * Gets the remote address for the actor, if any, else None.
   */
  def getRemoteAddress(): Option[InetSocketAddress] = actorRef.remoteAddress

  /**
   * Returns the home address and port for this actor.
   */
  def getHomeAddress(): InetSocketAddress = actorRef.homeAddress

  /**
   * Set the home address and port for this actor.
   */
  def setHomeAddress(hostnameAndPort: Tuple2[String, Int]): Unit = actorRef.homeAddress = hostnameAndPort

  /**
   * Set the home address and port for this actor.
   */
  def setHomeAddress(address: InetSocketAddress): Unit = actorRef.homeAddress = address

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   */
  def link(actor: UntypedActorRef): Unit = actorRef.link(actor.actorRef)

  /**
   * Unlink the actor.
   */
  def unlink(actor: UntypedActorRef): Unit = actorRef.unlink(actor.actorRef)

  /**
   * Atomically start and link an actor.
   */
  def startLink(actor: UntypedActorRef): Unit = actorRef.startLink(actor.actorRef)

  /**
   * Atomically start, link and make an actor remote.
   */
  def startLinkRemote(actor: UntypedActorRef, hostname: String, port: Int): Unit =
    actorRef.startLinkRemote(actor.actorRef, hostname, port)

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawn(clazz: Class[_]): ActorRef = actorRef.guard.withGuard {
    val actorRef = spawnButDoNotStart(clazz)
    actorRef.start
    actorRef
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote(clazz: Class[_], hostname: String, port: Int): ActorRef = actorRef.guard.withGuard {
    val actor = spawnButDoNotStart(clazz)
    actor.makeRemote(hostname, port)
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLink(clazz: Class[_]): ActorRef = actorRef.guard.withGuard {
    val actor = spawnButDoNotStart(clazz)
    try {
      actor.start
    } finally {
      actorRef.link(actor)
    }
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLinkRemote(clazz: Class[_], hostname: String, port: Int): ActorRef = actorRef.guard.withGuard {
    val actor = spawnButDoNotStart(clazz)
    try {
      actor.makeRemote(hostname, port)
      actor.start
    } finally {
      actorRef.link(actor)
    }
  }

  /**
   * Returns the mailbox size.
   */
  def getMailboxSize(): Int = actorRef.mailboxSize

  /**
   * Returns the current supervisor if there is one, null if not.
   */
  def getSupervisor(): UntypedActorRef = UntypedActorRef.wrap(actorRef.supervisor.getOrElse(null))

  private def spawnButDoNotStart(clazz: Class[_]): ActorRef = actorRef.guard.withGuard {
    val actor = UntypedActor.actorOf(clazz)
    if (!actorRef.dispatcher.isInstanceOf[ThreadBasedDispatcher]) actor.actorRef.dispatcher = actorRef.dispatcher
    actorRef
  }
}
