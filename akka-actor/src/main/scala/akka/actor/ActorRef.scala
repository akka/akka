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
import scala.annotation.tailrec

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
  scalaRef: InternalActorRef ⇒
  // Only mutable for RemoteServer in order to maintain identity across nodes

  /**
   * Returns the path for this actor (from this actor up to the root actor).
   */
  def path: ActorPath

  /**
   * Comparison only takes address into account.
   */
  final def compareTo(other: ActorRef) = this.path compareTo other.path

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
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop(): Unit

  /**
   * Is the actor shut down?
   */
  def isTerminated: Boolean

  // FIXME RK check if we should scramble the bits or whether they can stay the same
  final override def hashCode: Int = path.hashCode

  final override def equals(that: Any): Boolean = that match {
    case other: ActorRef ⇒ path == other.path
    case _               ⇒ false
  }

  override def toString = "Actor[%s]".format(path)
}

/**
 * This trait represents the Scala Actor API
 * There are implicit conversions in ../actor/Implicits.scala
 * from ActorRef -> ScalaActorRef and back
 */
trait ScalaActorRef { ref: ActorRef ⇒

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
}

/**
 * Internal trait for assembling all the functionality needed internally on
 * ActorRefs. NOTE THAT THIS IS NOT A STABLE EXTERNAL INTERFACE!
 *
 * DO NOT USE THIS UNLESS INTERNALLY WITHIN AKKA!
 */
private[akka] abstract class InternalActorRef extends ActorRef with ScalaActorRef {
  def resume(): Unit
  def suspend(): Unit
  def restart(cause: Throwable): Unit
  def sendSystemMessage(message: SystemMessage): Unit
  def getParent: InternalActorRef
  /**
   * Obtain ActorRef by possibly traversing the actor tree or looking it up at
   * some provider-specific location. This method shall return the end result,
   * i.e. not only the next step in the look-up; this will typically involve
   * recursive invocation. A path element of ".." signifies the parent, a
   * trailing "" element must be disregarded. If the requested path does not
   * exist, return Nobody.
   */
  def getChild(name: Iterator[String]): InternalActorRef
}

private[akka] case object Nobody extends MinimalActorRef {
  val path = new RootActorPath(new LocalAddress("all-systems"), "/Nobody")
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka] (
  system: ActorSystemImpl,
  _props: Props,
  _supervisor: InternalActorRef,
  val path: ActorPath,
  val systemService: Boolean = false,
  _receiveTimeout: Option[Duration] = None,
  _hotswap: Stack[PartialFunction[Any, Unit]] = Props.noHotSwap)
  extends InternalActorRef {

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

  protected def actorContext: ActorContext = actorCell

  /**
   * Is the actor terminated?
   * If this method returns true, it will never return false again, but if it
   * returns false, you cannot be sure if it's alive still (race condition)
   */
  override def isTerminated: Boolean = actorCell.isTerminated

  /**
   * Suspends the actor so that it will not process messages until resumed. The
   * suspend request is processed asynchronously to the caller of this method
   * as well as to normal message sends: the only ordering guarantee is that
   * message sends done from the same thread after calling this method will not
   * be processed until resumed.
   */
  //FIXME TODO REMOVE THIS, NO REPLACEMENT, ticket #1415
  def suspend(): Unit = actorCell.suspend()

  /**
   * Resumes a suspended actor.
   */
  //FIXME TODO REMOVE THIS, NO REPLACEMENT, ticket #1415
  def resume(): Unit = actorCell.resume()

  /**
   * Shuts down the actor and its message queue
   */
  def stop(): Unit = actorCell.stop()

  def getParent: InternalActorRef = actorCell.parent

  /**
   * Method for looking up a single child beneath this actor. Override in order
   * to inject “synthetic” actor paths like “/temp”.
   */
  protected def getSingleChild(name: String): InternalActorRef = {
    if (actorCell.isTerminated) Nobody // read of the mailbox status ensures we get the latest childrenRefs
    else {
      val children = actorCell.childrenRefs
      if (children contains name) children(name).child.asInstanceOf[InternalActorRef]
      else Nobody
    }
  }

  def getChild(names: Iterator[String]): InternalActorRef = {
    /*
     * The idea is to recursively descend as far as possible with LocalActor
     * Refs and hand over to that “foreign” child when we encounter it.
     */
    @tailrec
    def rec(ref: InternalActorRef, name: Iterator[String]): InternalActorRef =
      ref match {
        case l: LocalActorRef ⇒
          val n = name.next()
          val next = n match {
            case ".." ⇒ l.getParent
            case ""   ⇒ l
            case _    ⇒ l.getSingleChild(n)
          }
          if (next == Nobody || name.isEmpty) next else rec(next, name)
        case _ ⇒
          ref.getChild(name)
      }
    if (names.isEmpty) this
    else rec(this, names)
  }

  // ========= AKKA PROTECTED FUNCTIONS =========

  protected[akka] def underlying: ActorCell = actorCell

  // FIXME TODO: remove this method. It is used in testkit.
  // @deprecated("This method does a spin-lock to block for the actor, which might never be there, do not use this", "2.0")
  protected[akka] def underlyingActorInstance: Actor = {
    var instance = actorCell.actor
    while ((instance eq null) && !actorCell.isTerminated) {
      try { Thread.sleep(1) } catch { case i: InterruptedException ⇒ }
      instance = actorCell.actor
    }
    instance
  }

  def sendSystemMessage(message: SystemMessage) { underlying.dispatcher.systemDispatch(underlying, message) }

  def !(message: Any)(implicit sender: ActorRef = null): Unit = actorCell.tell(message, sender)

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = actorCell.provider.ask(message, this, timeout)

  def restart(cause: Throwable): Unit = actorCell.restart(cause)

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path.toString)
}

/**
 * Memento pattern for serializing ActorRefs transparently
 */
case class SerializedActorRef(path: String) {
  import akka.serialization.Serialization.currentSystem

  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = currentSystem.value match {
    case null ⇒ throw new IllegalStateException(
      "Trying to deserialize a serialized ActorRef without an ActorSystem in scope." +
        " Use 'akka.serialization.Serialization.currentSystem.withValue(system) { ... }'")
    case someSystem ⇒ someSystem.actorFor(path)
  }
}

/**
 * Trait for ActorRef implementations where all methods contain default stubs.
 */
trait MinimalActorRef extends InternalActorRef {

  def getParent: InternalActorRef = Nobody
  def getChild(names: Iterator[String]): InternalActorRef = {
    val dropped = names.dropWhile(_.isEmpty)
    if (dropped.isEmpty) this
    else Nobody
  }

  //FIXME REMOVE THIS, ticket #1416
  //FIXME REMOVE THIS, ticket #1415
  def suspend(): Unit = ()
  def resume(): Unit = ()

  def stop(): Unit = ()

  def isTerminated = false

  def !(message: Any)(implicit sender: ActorRef = null): Unit = ()

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    throw new UnsupportedOperationException("Not supported for [%s]".format(getClass.getName))

  def sendSystemMessage(message: SystemMessage): Unit = ()
  def restart(cause: Throwable): Unit = ()
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
    _path = rootPath / "null"
    brokenPromise = new KeptPromise[Any](Left(new ActorKilledException("In DeadLetterActorRef - promises are always broken.")))(dispatcher)
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

class AskActorRef(
  val path: ActorPath,
  override val getParent: InternalActorRef,
  deathWatch: DeathWatch,
  timeout: Timeout,
  val dispatcher: MessageDispatcher) extends MinimalActorRef {

  final val result = new DefaultPromise[Any](timeout)(dispatcher)

  {
    val callback: Future[Any] ⇒ Unit = { _ ⇒ deathWatch.publish(Terminated(AskActorRef.this)); whenDone() }
    result onComplete callback
    result onTimeout callback
  }

  protected def whenDone(): Unit = ()

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = message match {
    case Status.Success(r) ⇒ result.completeWithResult(r)
    case Status.Failure(f) ⇒ result.completeWithException(f)
    case other             ⇒ result.completeWithResult(other)
  }

  override def sendSystemMessage(message: SystemMessage): Unit = message match {
    case _: Terminate ⇒ stop()
    case _            ⇒
  }

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    new KeptPromise[Any](Left(new UnsupportedOperationException("Ask/? is not supported for %s".format(getClass.getName))))(dispatcher)

  override def isTerminated = result.isCompleted || result.isExpired

  override def stop(): Unit = if (!isTerminated) result.completeWithException(new ActorKilledException("Stopped"))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path.toString)
}
