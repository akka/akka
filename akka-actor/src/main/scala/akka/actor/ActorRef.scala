/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.util._
import scala.collection.immutable.Stack
import java.lang.{ UnsupportedOperationException, IllegalStateException }
import akka.AkkaApplication
import akka.event.ActorEventBus

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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class ActorRef extends ActorRefShared with UntypedChannel with ReplyChannel[Any] with java.lang.Comparable[ActorRef] with Serializable {
  scalaRef: ScalaActorRef ⇒
  // Only mutable for RemoteServer in order to maintain identity across nodes

  private[akka] def uuid: Uuid

  def address: String

  /**
   * Comparison only takes address into account.
   */
  def compareTo(other: ActorRef) = this.address compareTo other.address

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the specified timeout (milliseconds)
   */
  def ask(message: AnyRef, timeout: Long): Future[Any] = ask(message, timeout, null)

  /**
   * Akka Java API. <p/>
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'tell' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>ask</code> then you <b>have to</b> use <code>getContext().channel().tell(...)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def ask(message: AnyRef, timeout: Long, sender: ActorRef): Future[AnyRef] =
    ?(message, Timeout(timeout))(sender).asInstanceOf[Future[AnyRef]]

  /**
   * Akka Java API. <p/>
   * Forwards the message specified to this actor and preserves the original sender of the message
   */
  def forward(message: AnyRef, sender: ActorRef) {
    if (sender eq null) throw new IllegalArgumentException("The 'sender' argument to 'forward' can't be null")
    else forward(message)(ForwardableChannel(sender))
  }

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
  def isShutdown: Boolean

  /**
   * Registers this actor to be a death monitor of the provided ActorRef
   * This means that this actor will get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @returns the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def startsMonitoring(subject: ActorRef): ActorRef //TODO FIXME REMOVE THIS

  /**
   * Deregisters this actor from being a death monitor of the provided ActorRef
   * This means that this actor will not get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @returns the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def stopsMonitoring(subject: ActorRef): ActorRef //TODO FIXME REMOVE THIS

  override def hashCode: Int = HashCode.hash(HashCode.SEED, address)

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[ActorRef] &&
      that.asInstanceOf[ActorRef].address == address
  }

  override def toString = "Actor[%s]".format(address)
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka] (
  app: AkkaApplication,
  props: Props,
  givenAddress: String, //Never refer to this internally instead use "address"
  val systemService: Boolean = false,
  private[akka] val uuid: Uuid = newUuid,
  receiveTimeout: Option[Long] = None,
  hotswap: Stack[PartialFunction[Any, Unit]] = Props.noHotSwap)
  extends ActorRef with ScalaActorRef {

  final val address: String = givenAddress match {
    case null | Props.randomAddress ⇒ uuid.toString
    case other                      ⇒ other
  }

  private[this] val actorCell = new ActorCell(app, this, props, receiveTimeout, hotswap)
  actorCell.start()

  /**
   * Is the actor shut down?
   * If this method returns true, it will never return false again, but if it returns false, you cannot be sure if it's alive still (race condition)
   */
  //FIXME TODO RENAME TO isTerminated
  def isShutdown: Boolean = actorCell.isShutdown

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
   * @returns the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def startsMonitoring(subject: ActorRef): ActorRef = actorCell.startsMonitoring(subject)

  /**
   * Deregisters this actor from being a death monitor of the provided ActorRef
   * This means that this actor will not get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @returns the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def stopsMonitoring(subject: ActorRef): ActorRef = actorCell.stopsMonitoring(subject)

  // ========= AKKA PROTECTED FUNCTIONS =========

  protected[akka] def underlying: ActorCell = actorCell

  // FIXME TODO: remove this method
  // @deprecated("This method does a spin-lock to block for the actor, which might never be there, do not use this", "2.0")
  protected[akka] def underlyingActorInstance: Actor = {
    var instance = actorCell.actor
    while ((instance eq null) && !actorCell.isShutdown) {
      try { Thread.sleep(1) } catch { case i: InterruptedException ⇒ }
      instance = actorCell.actor
    }
    instance
  }

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit =
    actorCell.postMessageToMailbox(message, channel)

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = {
    actorCell.postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, channel)
  }

  protected[akka] def handleFailure(fail: Failed): Unit = actorCell.handleFailure(fail)

  protected[akka] def restart(cause: Throwable): Unit = actorCell.restart(cause)

  // ========= PRIVATE FUNCTIONS =========

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = {
    // TODO: this was used to really send LocalActorRef across the network, which is broken now
    val inetaddr = app.defaultAddress
    SerializedActorRef(uuid, address, inetaddr.getAddress.getHostAddress, inetaddr.getPort)
  }
}

/**
 * This trait represents the common (external) methods for all ActorRefs
 * Needed because implicit conversions aren't applied when instance imports are used
 *
 * i.e.
 * var self: ScalaActorRef = ...
 * import self._
 * //can't call ActorRef methods here unless they are declared in a common
 * //superclass, which ActorRefShared is.
 */
trait ActorRefShared {

  /**
   * Returns the address for the actor.
   */
  def address: String
}

/**
 * This trait represents the Scala Actor API
 * There are implicit conversions in ../actor/Implicits.scala
 * from ActorRef -> ScalaActorRef and back
 */
trait ScalaActorRef extends ActorRefShared with ReplyChannel[Any] { ref: ActorRef ⇒

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
  def !(message: Any)(implicit channel: UntypedChannel): Unit = postMessageToMailbox(message, channel)

  /**
   * Sends a message asynchronously, returning a future which may eventually hold the reply.
   */
  def ?(message: Any)(implicit channel: UntypedChannel, timeout: Timeout): Future[Any] = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, channel)

  def ?(message: Any, timeout: Timeout)(implicit channel: UntypedChannel): Future[Any] = ?(message)(channel, timeout)

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!' and '?'/'ask'.
   */
  def forward(message: Any)(implicit forwardable: ForwardableChannel) = postMessageToMailbox(message, forwardable.channel)

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any]

  protected[akka] def restart(cause: Throwable): Unit
}

/**
 * Memento pattern for serializing ActorRefs transparently
 */

case class SerializedActorRef(uuid: Uuid, address: String, hostname: String, port: Int) {

  import akka.serialization.Serialization.app

  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = {
    if (app.value eq null) throw new IllegalStateException(
      "Trying to deserialize a serialized ActorRef without an AkkaApplication in scope." +
        " Use akka.serialization.Serialization.app.withValue(akkaApplication) { ... }")
    app.value.provider.deserialize(this) match {
      case Some(actor) ⇒ actor
      case None        ⇒ throw new IllegalStateException("Could not deserialize ActorRef")
    }
  }
}

/**
 * Trait for ActorRef implementations where most of the methods are not supported.
 */
trait UnsupportedActorRef extends ActorRef with ScalaActorRef {

  def startsMonitoring(actorRef: ActorRef): ActorRef = unsupported

  def stopsMonitoring(actorRef: ActorRef): ActorRef = unsupported

  def suspend(): Unit = unsupported

  def resume(): Unit = unsupported

  protected[akka] def restart(cause: Throwable): Unit = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for %s".format(getClass.getName))
}

case class DeadLetter(message: Any, channel: UntypedChannel)

class DeadLetterActorRef(app: AkkaApplication) extends UnsupportedActorRef {
  val brokenPromise = new KeptPromise[Any](Left(new ActorKilledException("In DeadLetterActorRef, promises are always broken.")))(app.dispatcher)
  val address: String = "akka:internal:DeadLetterActorRef"

  private[akka] val uuid: akka.actor.Uuid = new com.eaio.uuid.UUID(0L, 0L) //Nil UUID

  override def startsMonitoring(actorRef: ActorRef): ActorRef = actorRef

  override def stopsMonitoring(actorRef: ActorRef): ActorRef = actorRef

  def isShutdown(): Boolean = true

  def stop(): Unit = ()

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = app.eventHandler.notify(DeadLetter(message, channel))

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = { app.eventHandler.notify(DeadLetter(message, channel)); brokenPromise }
}
