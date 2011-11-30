/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.util._
import scala.collection.immutable.Stack
import java.lang.{ UnsupportedOperationException, IllegalStateException }
import akka.serialization.Serialization
import java.net.InetSocketAddress
import akka.remote.RemoteAddress
import java.util.concurrent.TimeUnit
import akka.event.EventStream
import akka.event.DeathWatch

/**
 * ActorRef is an immutable and serializable handle to an Actor.
 * <p/>
 * Create an ActorRef for an Actor by using the factory method on the Actor object.
 * <p/>
 * Here is an example on how to create an actor with a default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf[MyActor]
 *   actor ! message
 *   actor.stop()
 * </pre>
 *
 * You can also create and start actors like this:
 * <pre>
 *   val actor = actorOf[MyActor]
 * </pre>
 *
 * Here is an example on how to create an actor with a non-default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf(new MyActor(...))
 *   actor ! message
 *   actor.stop()
 * </pre>
 *
 * The natural ordering of ActorRef is defined in terms of its [[akka.actor.ActorPath]].
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class ActorRef extends java.lang.Comparable[ActorRef] with Serializable {
  scalaRef: ScalaActorRef ⇒
  // Only mutable for RemoteServer in order to maintain identity across nodes

  /**
   * Returns the path for this actor (from this actor up to the root actor).
   */
  def path: ActorPath

  /**
   * Comparison only takes address into account.
   */
  def compareTo(other: ActorRef) = this.path compareTo other.path

  /**
   * Sends the specified message to the sender, i.e. fire-and-forget semantics.<p/>
   * <pre>
   * actor.tell(message);
   * </pre>
   */
  final def tell(msg: Any): Unit = this.!(msg)(null: ActorRef)

  /**
   * Java API. <p/>
   * Sends the specified message to the sender, i.e. fire-and-forget
   * semantics, including the sender reference if possible (not supported on
   * all senders).<p/>
   * <pre>
   * actor.tell(message, context);
   * </pre>
   */
  final def tell(msg: Any, sender: ActorRef): Unit = this.!(msg)(sender)

  /**
   * Akka Java API. <p/>
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'tell' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>ask</code> then you <b>have to</b> use <code>getContext().sender().tell(...)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def ask(message: AnyRef, timeout: Long): Future[AnyRef] = ?(message, Timeout(timeout)).asInstanceOf[Future[AnyRef]]

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!' and '?'/'ask'.
   */
  def forward(message: Any)(implicit context: ActorContext) = tell(message, context.sender)

  /**
   * Suspends the actor. It will not process messages while suspended.
   */
  def suspend(): Unit //TODO FIXME REMOVE THIS

  /**
   * Resumes a suspended actor.
   */
  def resume(): Unit //TODO FIXME REMOVE THIS

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop(): Unit

  /**
   * Is the actor shut down?
   */
  def isTerminated: Boolean

  /**
   * Registers this actor to be a death monitor of the provided ActorRef
   * This means that this actor will get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @return the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def startsWatching(subject: ActorRef): ActorRef //TODO FIXME REMOVE THIS

  /**
   * Deregisters this actor from being a death monitor of the provided ActorRef
   * This means that this actor will not get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @return the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def stopsWatching(subject: ActorRef): ActorRef //TODO FIXME REMOVE THIS

  // FIXME check if we should scramble the bits or whether they can stay the same
  override def hashCode: Int = path.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: ActorRef ⇒ path == other.path
    case _               ⇒ false
  }

  override def toString = "Actor[%s]".format(path)
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka] (
  system: ActorSystemImpl,
  _props: Props,
  _supervisor: ActorRef,
  val path: ActorPath,
  val systemService: Boolean = false,
  _receiveTimeout: Option[Long] = None,
  _hotswap: Stack[PartialFunction[Any, Unit]] = Props.noHotSwap)
  extends ActorRef with ScalaActorRef {

  /*
   * actorCell.start() publishes actorCell & this to the dispatcher, which
   * means that messages may be processed theoretically before the constructor
   * ends. The JMM guarantees visibility for final fields only after the end
   * of the constructor, so publish the actorCell safely by making it a
   * @volatile var which is NOT TO BE WRITTEN TO. The alternative would be to
   * move start() outside of the constructor, which would basically require
   * us to use purely factory methods for creating LocalActorRefs.
   */
  @volatile
  private var actorCell = new ActorCell(system, this, _props, _supervisor, _receiveTimeout, _hotswap)
  actorCell.start()

  /**
   * Is the actor terminated?
   * If this method returns true, it will never return false again, but if it returns false, you cannot be sure if it's alive still (race condition)
   */
  override def isTerminated: Boolean = actorCell.isTerminated

  /**
   * Suspends the actor so that it will not process messages until resumed. The
   * suspend request is processed asynchronously to the caller of this method
   * as well as to normal message sends: the only ordering guarantee is that
   * message sends done from the same thread after calling this method will not
   * be processed until resumed.
   */
  //FIXME TODO REMOVE THIS, NO REPLACEMENT
  def suspend(): Unit = actorCell.suspend()

  /**
   * Resumes a suspended actor.
   */
  //FIXME TODO REMOVE THIS, NO REPLACEMENT
  def resume(): Unit = actorCell.resume()

  /**
   * Shuts down the actor and its message queue
   */
  def stop(): Unit = actorCell.stop()

  /**
   * Registers this actor to be a death monitor of the provided ActorRef
   * This means that this actor will get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @return the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def startsWatching(subject: ActorRef): ActorRef = actorCell.startsWatching(subject)

  /**
   * Deregisters this actor from being a death monitor of the provided ActorRef
   * This means that this actor will not get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @return the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def stopsWatching(subject: ActorRef): ActorRef = actorCell.stopsWatching(subject)

  // ========= AKKA PROTECTED FUNCTIONS =========

  protected[akka] def underlying: ActorCell = actorCell

  // FIXME TODO: remove this method
  // @deprecated("This method does a spin-lock to block for the actor, which might never be there, do not use this", "2.0")
  protected[akka] def underlyingActorInstance: Actor = {
    var instance = actorCell.actor
    while ((instance eq null) && !actorCell.isTerminated) {
      try { Thread.sleep(1) } catch { case i: InterruptedException ⇒ }
      instance = actorCell.actor
    }
    instance
  }

  protected[akka] def sendSystemMessage(message: SystemMessage) { underlying.dispatcher.systemDispatch(underlying, message) }

  def !(message: Any)(implicit sender: ActorRef = null): Unit = actorCell.tell(message, sender)

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = actorCell.provider.ask(message, this, timeout)

  protected[akka] override def restart(cause: Throwable): Unit = actorCell.restart(cause)

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = actorCell.provider.serialize(this)
}

/**
 * This trait represents the Scala Actor API
 * There are implicit conversions in ../actor/Implicits.scala
 * from ActorRef -> ScalaActorRef and back
 */
trait ScalaActorRef { ref: ActorRef ⇒

  protected[akka] def sendSystemMessage(message: SystemMessage): Unit

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   *
   * If invoked from within an actor then the actor reference is implicitly passed on as the implicit 'sender' argument.
   * <p/>
   *
   * This actor 'sender' reference is then available in the receiving actor in the 'sender' member variable,
   * if invoked from within an Actor. If not then no sender is available.
   * <pre>
   *   actor ! message
   * </pre>
   * <p/>
   */
  def !(message: Any)(implicit sender: ActorRef = null): Unit

  /**
   * Sends a message asynchronously, returning a future which may eventually hold the reply.
   */
  def ?(message: Any)(implicit timeout: Timeout): Future[Any]

  /**
   * Sends a message asynchronously, returning a future which may eventually hold the reply.
   * The implicit parameter with the default value is just there to disambiguate it from the version that takes the
   * implicit timeout
   */
  def ?(message: Any, timeout: Timeout)(implicit ignore: Int = 0): Future[Any] = ?(message)(timeout)

  protected[akka] def restart(cause: Throwable): Unit
}

/**
 * Memento pattern for serializing ActorRefs transparently
 */
// FIXME: remove and replace by ActorPath.toString
case class SerializedActorRef(hostname: String, port: Int, path: String) {
  import akka.serialization.Serialization.currentSystem

  // FIXME this is broken, but see above
  def this(address: Address, path: String) = this(address.hostPort, 0, path)
  def this(remoteAddress: RemoteAddress, path: String) = this(remoteAddress.host, remoteAddress.port, path)
  def this(remoteAddress: InetSocketAddress, path: String) = this(remoteAddress.getAddress.getHostAddress, remoteAddress.getPort, path) //TODO FIXME REMOVE

  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = currentSystem.value match {
    case null ⇒ throw new IllegalStateException(
      "Trying to deserialize a serialized ActorRef without an ActorSystem in scope." +
        " Use akka.serialization.Serialization.currentSystem.withValue(system) { ... }")
    case someSystem ⇒ someSystem.provider.deserialize(this) match {
      case Some(actor) ⇒ actor
      case None        ⇒ throw new IllegalStateException("Could not deserialize ActorRef")
    }
  }
}

/**
 * Trait for ActorRef implementations where all methods contain default stubs.
 */
trait MinimalActorRef extends ActorRef with ScalaActorRef {

  def startsWatching(actorRef: ActorRef): ActorRef = actorRef
  def stopsWatching(actorRef: ActorRef): ActorRef = actorRef

  def suspend(): Unit = ()
  def resume(): Unit = ()

  def stop(): Unit = ()

  def isTerminated = false

  def !(message: Any)(implicit sender: ActorRef = null): Unit = ()

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    throw new UnsupportedOperationException("Not supported for %s".format(getClass.getName))

  protected[akka] def sendSystemMessage(message: SystemMessage): Unit = ()
  protected[akka] def restart(cause: Throwable): Unit = ()
}

object MinimalActorRef {
  def apply(_path: ActorPath)(receive: PartialFunction[Any, Unit]): ActorRef = new MinimalActorRef {
    def path = _path
    override def !(message: Any)(implicit sender: ActorRef = null): Unit =
      if (receive.isDefinedAt(message)) receive(message)
  }
}

case class DeadLetter(message: Any, sender: ActorRef, recipient: ActorRef)

object DeadLetterActorRef {
  class SerializedDeadLetterActorRef extends Serializable { //TODO implement as Protobuf for performance?
    @throws(classOf[java.io.ObjectStreamException])
    private def readResolve(): AnyRef = Serialization.currentSystem.value.deadLetters
  }

  val serialized = new SerializedDeadLetterActorRef
}

class DeadLetterActorRef(val eventStream: EventStream) extends MinimalActorRef {
  @volatile
  private var brokenPromise: Future[Any] = _
  @volatile
  private var _path: ActorPath = _
  def path: ActorPath = {
    assert(_path != null)
    _path
  }

  private[akka] def init(dispatcher: MessageDispatcher, rootPath: ActorPath) {
    _path = rootPath / "nul"
    brokenPromise = new KeptPromise[Any](Left(new ActorKilledException("In DeadLetterActorRef, promises are always broken.")))(dispatcher)
  }

  override def isTerminated(): Boolean = true

  override def !(message: Any)(implicit sender: ActorRef = this): Unit = message match {
    case d: DeadLetter ⇒ eventStream.publish(d)
    case _             ⇒ eventStream.publish(DeadLetter(message, sender, this))
  }

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = {
    eventStream.publish(DeadLetter(message, this, this))
    // leave this in: guard with good visibility against really stupid/weird errors
    assert(brokenPromise != null)
    brokenPromise
  }

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = DeadLetterActorRef.serialized
}

class AskActorRef(val path: ActorPath, provider: ActorRefProvider, deathWatch: DeathWatch, timeout: Timeout, val dispatcher: MessageDispatcher) extends MinimalActorRef {
  final val result = new DefaultPromise[Any](timeout)(dispatcher)

  {
    val callback: Future[Any] ⇒ Unit = { _ ⇒ deathWatch.publish(Terminated(AskActorRef.this)); whenDone() }
    result onComplete callback
    result onTimeout callback
  }

  protected def whenDone(): Unit = {}

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = message match {
    case Status.Success(r) ⇒ result.completeWithResult(r)
    case Status.Failure(f) ⇒ result.completeWithException(f)
    case other             ⇒ result.completeWithResult(other)
  }

  protected[akka] override def sendSystemMessage(message: SystemMessage): Unit = message match {
    case _: Terminate ⇒ stop()
    case _            ⇒
  }

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    new KeptPromise[Any](Left(new UnsupportedOperationException("Ask/? is not supported for %s".format(getClass.getName))))(dispatcher)

  override def isTerminated = result.isCompleted || result.isExpired

  override def stop(): Unit = if (!isTerminated) result.completeWithException(new ActorKilledException("Stopped"))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = provider.serialize(this)
}
