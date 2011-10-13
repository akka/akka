/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.util._
import akka.serialization.{ Serializer, Serialization }
import ReflectiveAccess._
import ClusterModule._
import java.net.InetSocketAddress
import scala.collection.immutable.Stack
import java.lang.{ UnsupportedOperationException, IllegalStateException }
import akka.event.{ EventHandler, InVMMonitoring }

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

  private[akka] val uuid = newUuid

  def address: String

  /**
   * Comparison only takes address into account.
   */
  def compareTo(other: ActorRef) = this.address compareTo other.address

  protected[akka] def timeout: Long = Props.defaultTimeout.duration.toMillis //TODO Remove me if possible

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout()) and omits the sender
   */
  def ask(message: AnyRef): Future[AnyRef] = ask(message, timeout, null)

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the specified timeout (milliseconds)
   */
  def ask(message: AnyRef, timeout: Long): Future[Any] = ask(message, timeout, null)

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout())
   */
  def ask(message: AnyRef, sender: ActorRef): Future[AnyRef] = ask(message, timeout, sender)

  /**
   * Akka Java API. <p/>
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'tell' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>ask</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
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
  def suspend(): Unit

  /**
   * Resumes a suspended actor.
   */
  def resume(): Unit

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop(): Unit

  /**
   * Is the actor shut down?
   */
  def isShutdown: Boolean

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field of the 'faultHandler' has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   */
  def link(actorRef: ActorRef): ActorRef

  /**
   * Unlink the actor.
   */
  def unlink(actorRef: ActorRef): ActorRef

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any]

  protected[akka] def restart(cause: Throwable): Unit

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
  private[this] val props: Props,
  givenAddress: String,
  val systemService: Boolean = false,
  override private[akka] val uuid: Uuid = newUuid,
  receiveTimeout: Option[Long] = None,
  hotswap: Stack[PartialFunction[Any, Unit]] = Stack.empty)
  extends ActorRef with ScalaActorRef {

  // used only for deserialization
  private[akka] def this(
    __uuid: Uuid,
    __address: String,
    __props: Props,
    __receiveTimeout: Option[Long],
    __hotswap: Stack[PartialFunction[Any, Unit]]) = {

    this(__props, __address, false, __uuid, __receiveTimeout, __hotswap)

    actorCell.setActorContext(actorCell) // this is needed for deserialization - why?
  }

  final def address: String = givenAddress match {
    case null | "" ⇒ uuid.toString
    case other     ⇒ other
  }

  private[this] val actorCell = new ActorCell(this, props, receiveTimeout, hotswap)
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
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field of the 'faultHandler' has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself.
   * Returns the ref that was passed into it
   */
  def link(subject: ActorRef): ActorRef = actorCell.link(subject)

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   * Returns the ref that was passed into it
   */
  def unlink(subject: ActorRef): ActorRef = actorCell.unlink(subject)

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

  protected[akka] override def timeout: Long = props.timeout.duration.toMillis // TODO: remove this if possible

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
    val inetaddr =
      if (ReflectiveAccess.RemoteModule.isEnabled) Actor.remote.address
      else ReflectiveAccess.RemoteModule.configDefaultAddress
    SerializedActorRef(uuid, address, inetaddr.getAddress.getHostAddress, inetaddr.getPort, timeout)
  }
}

/**
 * System messages for RemoteActorRef.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteActorSystemMessage {
  val Stop = "RemoteActorRef:stop".intern
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
  val remoteAddress: InetSocketAddress,
  val address: String,
  _timeout: Long,
  loader: Option[ClassLoader])
  extends ActorRef with ScalaActorRef {

  @volatile
  private var running: Boolean = true

  def isShutdown: Boolean = !running

  RemoteModule.ensureEnabled()

  protected[akka] override def timeout: Long = _timeout

  def postMessageToMailbox(message: Any, channel: UntypedChannel) {
    val chSender = if (channel.isInstanceOf[ActorRef]) Some(channel.asInstanceOf[ActorRef]) else None
    Actor.remote.send[Any](message, chSender, None, remoteAddress, timeout, true, this, loader)
  }

  def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = {

    val chSender = if (channel.isInstanceOf[ActorRef]) Some(channel.asInstanceOf[ActorRef]) else None
    val chFuture = if (channel.isInstanceOf[Promise[_]]) Some(channel.asInstanceOf[Promise[Any]]) else None
    val future = Actor.remote.send[Any](message, chSender, chFuture, remoteAddress, timeout.duration.toMillis, false, this, loader)

    if (future.isDefined) ActorPromise(future.get)
    else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
  }

  def suspend(): Unit = unsupported

  def resume(): Unit = unsupported

  def stop() { //FIXME send the cause as well!
    synchronized {
      if (running) {
        running = false
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
      }
    }
  }

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = {
    SerializedActorRef(uuid, address, remoteAddress.getAddress.getHostAddress, remoteAddress.getPort, timeout)
  }

  def link(actorRef: ActorRef): ActorRef = unsupported

  def unlink(actorRef: ActorRef): ActorRef = unsupported

  protected[akka] def restart(cause: Throwable): Unit = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
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
}

/**
 * Memento pattern for serializing ActorRefs transparently
 */
case class SerializedActorRef(uuid: Uuid,
                              address: String,
                              hostname: String,
                              port: Int,
                              timeout: Long) {
  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = Actor.provider.actorFor(address) match {
    case Some(actor) ⇒ actor
    case None ⇒
      //TODO FIXME Add case for when hostname+port == remote.address.hostname+port, should return a DeadActorRef or something
      if (ReflectiveAccess.RemoteModule.isEnabled)
        RemoteActorRef(new InetSocketAddress(hostname, port), address, timeout, None)
      else
        throw new IllegalStateException(
          "Trying to deserialize ActorRef [" + this +
            "] but it's not found in the local registry and remoting is not enabled.")
  }
}

/**
 * Trait for ActorRef implementations where most of the methods are not supported.
 */
trait UnsupportedActorRef extends ActorRef with ScalaActorRef {

  def link(actorRef: ActorRef): ActorRef = unsupported

  def unlink(actorRef: ActorRef): ActorRef = unsupported

  def suspend(): Unit = unsupported

  def resume(): Unit = unsupported

  protected[akka] def restart(cause: Throwable): Unit = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for %s".format(getClass.getName))
}

object DeadLetterActorRef extends UnsupportedActorRef {
  val brokenPromise = new KeptPromise[Any](Left(new ActorKilledException("In DeadLetterActorRef, promises are always broken.")))
  val address: String = "akka:internal:DeadLetterActorRef"

  override def link(actorRef: ActorRef): ActorRef = actorRef

  override def unlink(actorRef: ActorRef): ActorRef = actorRef

  def isShutdown(): Boolean = true

  def stop(): Unit = ()

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = EventHandler.debug(this, message)

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = { EventHandler.debug(this, message); brokenPromise }
}
