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
abstract class ActorRef extends java.lang.Comparable[ActorRef] with Serializable {
  scalaRef: ScalaActorRef ⇒
  // Only mutable for RemoteServer in order to maintain identity across nodes

  /**
   * Returns the address for the actor.
   */
  def address: String

  private[akka] def uuid: Uuid //TODO FIXME REMOVE THIS

  /**
   * Comparison only takes address into account.
   */
  def compareTo(other: ActorRef) = this.address compareTo other.address

  /**
   * Sends the specified message to the sender, i.e. fire-and-forget semantics.<p/>
   * <pre>
   * actor.tell(message);
   * </pre>
   */
  def tell(msg: Any): Unit = this.!(msg)

  /**
   * Java API. <p/>
   * Sends the specified message to the sender, i.e. fire-and-forget
   * semantics, including the sender reference if possible (not supported on
   * all senders).<p/>
   * <pre>
   * actor.tell(message, context);
   * </pre>
   */
  def tell(msg: Any, sender: ActorRef): Unit = this.!(msg)(sender)

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
  def forward(message: Any)(implicit context: ActorContext) = postMessageToMailbox(message, context.sender)

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
  _supervisor: ActorRef,
  _givenAddress: String,
  val systemService: Boolean = false,
  private[akka] val uuid: Uuid = newUuid,
  receiveTimeout: Option[Long] = None,
  hotswap: Stack[PartialFunction[Any, Unit]] = Props.noHotSwap)
  extends ActorRef with ScalaActorRef {

  final val address: String = _givenAddress match {
    case null | Props.randomAddress ⇒ uuid.toString
    case other                      ⇒ other
  }

  private[this] val actorCell = new ActorCell(app, this, props, _supervisor, receiveTimeout, hotswap)
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

  protected[akka] def sendSystemMessage(message: SystemMessage) { underlying.dispatcher.systemDispatch(underlying, message) }

  protected[akka] def postMessageToMailbox(message: Any, sender: ActorRef): Unit = actorCell.postMessageToMailbox(message, sender)

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = app.provider.ask(message, this, timeout)

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
  def !(message: Any)(implicit sender: ActorRef = null): Unit = postMessageToMailbox(message, sender)

  /**
   * Sends a message asynchronously, returning a future which may eventually hold the reply.
   */
  def ?(message: Any)(implicit timeout: Timeout): Future[Any]

  def ?(message: Any, timeout: Timeout)(implicit ignore: Int = 0): Future[Any] = ?(message)(timeout)

  protected[akka] def postMessageToMailbox(message: Any, sender: ActorRef): Unit

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

  private[akka] final val uuid: akka.actor.Uuid = newUuid()

  def startsMonitoring(actorRef: ActorRef): ActorRef = actorRef

  def stopsMonitoring(actorRef: ActorRef): ActorRef = actorRef

  def suspend(): Unit = ()

  def resume(): Unit = ()

  protected[akka] def restart(cause: Throwable): Unit = ()

  protected[akka] def sendSystemMessage(message: SystemMessage): Unit = ()

  protected[akka] def postMessageToMailbox(msg: Any, sender: ActorRef): Unit = ()

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    throw new UnsupportedOperationException("Not supported for %s".format(getClass.getName))
}

/**
 * Trait for ActorRef implementations where all methods contain default stubs.
 */
trait MinimalActorRef extends ActorRef with ScalaActorRef {

  private[akka] val uuid: Uuid = newUuid()
  def address = uuid.toString

  def startsMonitoring(actorRef: ActorRef): ActorRef = actorRef
  def stopsMonitoring(actorRef: ActorRef): ActorRef = actorRef

  def suspend(): Unit = ()
  def resume(): Unit = ()

  protected[akka] def restart(cause: Throwable): Unit = ()
  def stop(): Unit = ()

  def isShutdown = false

  protected[akka] def sendSystemMessage(message: SystemMessage) {}

  protected[akka] def postMessageToMailbox(msg: Any, sender: ActorRef) {}
}

case class DeadLetter(message: Any, sender: ActorRef)

class DeadLetterActorRef(app: AkkaApplication) extends MinimalActorRef {
  val brokenPromise = new KeptPromise[Any](Left(new ActorKilledException("In DeadLetterActorRef, promises are always broken.")))(app.dispatcher)
  override val address: String = "akka:internal:DeadLetterActorRef"

  override def isShutdown(): Boolean = true

  protected[akka] override def postMessageToMailbox(message: Any, sender: ActorRef): Unit = app.eventHandler.notify(DeadLetter(message, sender))

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = brokenPromise
}

abstract class AskActorRef(promise: Promise[Any], app: AkkaApplication) extends MinimalActorRef {

  promise onComplete { _ ⇒ app.deathWatch.publish(Terminated(AskActorRef.this)); whenDone() }
  promise onTimeout { _ ⇒ app.deathWatch.publish(Terminated(AskActorRef.this)); whenDone() }

  protected def whenDone(): Unit

  override protected[akka] def postMessageToMailbox(message: Any, sender: ActorRef): Unit = message match {
    case akka.actor.Status.Success(r) ⇒ promise.completeWithResult(r)
    case akka.actor.Status.Failure(f) ⇒ promise.completeWithException(f)
    case other                        ⇒ promise.completeWithResult(other)
  }

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    new KeptPromise[Any](Left(new ActorKilledException("Not possible to ask/? a reference to an ask/?.")))(app.dispatcher)

  override def isShutdown = promise.isCompleted || promise.isExpired

  override def stop(): Unit = if (!isShutdown) promise.completeWithException(new ActorKilledException("Stopped"))
}
