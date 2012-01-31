/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.util._
import scala.collection.immutable.Stack
import java.lang.{ UnsupportedOperationException, IllegalStateException }
import akka.serialization.Serialization
import akka.event.EventStream
import scala.annotation.tailrec
import java.util.concurrent.{ ConcurrentHashMap }
import akka.event.LoggingAdapter

/**
 * Immutable and serializable handle to an actor, which may or may not reside
 * on the local host or inside the same [[akka.actor.ActorSystem]]. An ActorRef
 * can be obtained from an [[akka.actor.ActorRefFactory]], an interface which
 * is implemented by ActorSystem and [[akka.actor.ActorContext]]. This means
 * actors can be created top-level in the ActorSystem or as children of an
 * existing actor, but only from within that actor.
 *
 * ActorRefs can be freely shared among actors by message passing. Message
 * passing conversely is their only purpose, as demonstrated in the following
 * examples:
 *
 * Scala:
 * {{{
 * class ExampleActor extends Actor {
 *   val other = context.actorOf(Props[OtherActor], "childName") // will be destroyed and re-created upon restart by default
 *
 *   def receive {
 *     case Request1(msg) => other ! refine(msg)     // uses this actor as sender reference, reply goes to us
 *     case Request2(msg) => other.tell(msg, sender) // forward sender reference, enabling direct reply
 *     case Request3(msg) => sender ! (other ? msg)  // will reply with a Future for holding other’s reply (implicit timeout from "akka.actor.timeout")
 *   }
 * }
 * }}}
 *
 * Java:
 * {{{
 * public class ExampleActor Extends UntypedActor {
 *   // this child will be destroyed and re-created upon restart by default
 *   final ActorRef other = getContext().actorOf(new Props(OtherActor.class), "childName");
 *
 *   @Override
 *   public void onReceive(Object o) {
 *     if (o instanceof Request1) {
 *       val msg = ((Request1) o).getMsg();
 *       other.tell(msg);              // uses this actor as sender reference, reply goes to us
 *
 *     } else if (o instanceof Request2) {
 *       val msg = ((Request2) o).getMsg();
 *       other.tell(msg, getSender()); // forward sender reference, enabling direct reply
 *
 *     } else if (o instanceof Request3) {
 *       val msg = ((Request3) o).getMsg();
 *       getSender().tell(other.ask(msg, 5000)); // reply with Future for holding the other’s reply (timeout 5 seconds)
 *
 *     } else {
 *       unhandled(o);
 *     }
 *   }
 * }
 * }}}
 *
 * ActorRef does not have a method for terminating the actor it points to, use
 * [[akka.actor.ActorRefFactory]]`.stop(child)` for this purpose.
 */
abstract class ActorRef extends java.lang.Comparable[ActorRef] with Serializable {
  scalaRef: InternalActorRef ⇒

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
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!' and '?'/'ask'.
   */
  def forward(message: Any)(implicit context: ActorContext) = tell(message, context.sender)

  /**
   * Is the actor shut down?
   * The contract is that if this method returns true, then it will never be false again.
   * But you cannot rely on that it is alive if it returns true, since this by nature is a racy method.
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

}

/**
 * All ActorRefs have a scope which describes where they live. Since it is
 * often necessary to distinguish between local and non-local references, this
 * is the only method provided on the scope.
 */
trait ActorRefScope {
  def isLocal: Boolean
}

trait LocalRef extends ActorRefScope {
  final def isLocal = true
}

/**
 * Internal trait for assembling all the functionality needed internally on
 * ActorRefs. NOTE THAT THIS IS NOT A STABLE EXTERNAL INTERFACE!
 *
 * DO NOT USE THIS UNLESS INTERNALLY WITHIN AKKA!
 */
private[akka] abstract class InternalActorRef extends ActorRef with ScalaActorRef { this: ActorRefScope ⇒
  /*
   * Actor life-cycle management, invoked only internally (in response to user requests via ActorContext).
   */
  def resume(): Unit
  def suspend(): Unit
  def restart(cause: Throwable): Unit
  def stop(): Unit
  def sendSystemMessage(message: SystemMessage): Unit

  /**
   * Get a reference to the actor ref provider which created this ref.
   */
  def provider: ActorRefProvider

  /**
   * Obtain parent of this ref; used by getChild for ".." paths.
   */
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

  /**
   * Scope: if this ref points to an actor which resides within the same JVM,
   * i.e. whose mailbox is directly reachable etc.
   */
  def isLocal: Boolean
}

/**
 * This is an internal look-up failure token, not useful for anything else.
 */
private[akka] case object Nobody extends MinimalActorRef {
  val path = new RootActorPath(Address("akka", "all-systems"), "/Nobody")
  def provider = throw new UnsupportedOperationException("Nobody does not provide")
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 */
private[akka] class LocalActorRef private[akka] (
  _system: ActorSystemImpl,
  _props: Props,
  _supervisor: InternalActorRef,
  val path: ActorPath,
  val systemService: Boolean = false,
  _receiveTimeout: Option[Duration] = None)
  extends InternalActorRef with LocalRef {

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
  private var actorCell = newActorCell(_system, this, _props, _supervisor, _receiveTimeout)
  actorCell.start()

  protected def newActorCell(
    system: ActorSystemImpl,
    ref: InternalActorRef,
    props: Props,
    supervisor: InternalActorRef,
    receiveTimeout: Option[Duration]): ActorCell =
    new ActorCell(system, ref, props, supervisor, receiveTimeout)

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
  def suspend(): Unit = actorCell.suspend()

  /**
   * Resumes a suspended actor.
   */
  def resume(): Unit = actorCell.resume()

  /**
   * Shuts down the actor and its message queue
   */
  def stop(): Unit = actorCell.stop()

  def getParent: InternalActorRef = actorCell.parent

  def provider = actorCell.provider

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

  def sendSystemMessage(message: SystemMessage) { underlying.dispatcher.systemDispatch(underlying, message) }

  def !(message: Any)(implicit sender: ActorRef = null): Unit = actorCell.tell(message, sender)

  def restart(cause: Throwable): Unit = actorCell.restart(cause)

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(path)
}

/**
 * Memento pattern for serializing ActorRefs transparently
 */
case class SerializedActorRef private (path: String) {
  import akka.serialization.Serialization.currentSystem

  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = currentSystem.value match {
    case null ⇒
      throw new IllegalStateException(
        "Trying to deserialize a serialized ActorRef without an ActorSystem in scope." +
          " Use 'akka.serialization.Serialization.currentSystem.withValue(system) { ... }'")
    case someSystem ⇒
      someSystem.actorFor(path)
  }
}

object SerializedActorRef {
  def apply(path: ActorPath): SerializedActorRef = {
    Serialization.currentTransportAddress.value match {
      case null ⇒ new SerializedActorRef(path.toString)
      case addr ⇒ new SerializedActorRef(path.toStringWithAddress(addr))
    }
  }
}

/**
 * Trait for ActorRef implementations where all methods contain default stubs.
 */
private[akka] trait MinimalActorRef extends InternalActorRef with LocalRef {

  def getParent: InternalActorRef = Nobody

  def getChild(names: Iterator[String]): InternalActorRef = {
    val dropped = names.dropWhile(_.isEmpty)
    if (dropped.isEmpty) this
    else Nobody
  }

  def suspend(): Unit = ()
  def resume(): Unit = ()

  def stop(): Unit = ()

  def isTerminated = false

  def !(message: Any)(implicit sender: ActorRef = null): Unit = ()

  def sendSystemMessage(message: SystemMessage): Unit = ()
  def restart(cause: Throwable): Unit = ()

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(path)
}

private[akka] object MinimalActorRef {
  def apply(_path: ActorPath, _provider: ActorRefProvider)(receive: PartialFunction[Any, Unit]): ActorRef = new MinimalActorRef {
    def path = _path
    def provider = _provider
    override def !(message: Any)(implicit sender: ActorRef = null): Unit =
      if (receive.isDefinedAt(message)) receive(message)
  }
}

case class DeadLetter(message: Any, sender: ActorRef, recipient: ActorRef)

private[akka] object DeadLetterActorRef {
  class SerializedDeadLetterActorRef extends Serializable { //TODO implement as Protobuf for performance?
    @throws(classOf[java.io.ObjectStreamException])
    private def readResolve(): AnyRef = Serialization.currentSystem.value.deadLetters
  }

  val serialized = new SerializedDeadLetterActorRef
}

/**
 * This special dead letter reference has a name: it is that which is returned
 * by a local look-up which is unsuccessful.
 */
private[akka] class EmptyLocalActorRef(
  val provider: ActorRefProvider,
  val path: ActorPath,
  val eventStream: EventStream) extends MinimalActorRef {

  override def isTerminated(): Boolean = true

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = message match {
    case d: DeadLetter ⇒ // do NOT form endless loops, since deadLetters will resend!
    case _             ⇒ eventStream.publish(DeadLetter(message, sender, this))
  }
}

/**
 * Internal implementation of the dead letter destination: will publish any
 * received message to the eventStream, wrapped as [[akka.actor.DeadLetter]].
 */
private[akka] class DeadLetterActorRef(_provider: ActorRefProvider, _path: ActorPath, _eventStream: EventStream)
  extends EmptyLocalActorRef(_provider, _path, _eventStream) {

  override def !(message: Any)(implicit sender: ActorRef = this): Unit = message match {
    case d: DeadLetter ⇒ eventStream.publish(d)
    case _             ⇒ eventStream.publish(DeadLetter(message, sender, this))
  }

  @throws(classOf[java.io.ObjectStreamException])
  override protected def writeReplace(): AnyRef = DeadLetterActorRef.serialized
}

/**
 * Internal implementation detail used for paths like “/temp”
 */
private[akka] class VirtualPathContainer(
  val provider: ActorRefProvider,
  val path: ActorPath,
  override val getParent: InternalActorRef,
  val log: LoggingAdapter) extends MinimalActorRef {

  private val children = new ConcurrentHashMap[String, InternalActorRef]

  def addChild(name: String, ref: InternalActorRef): Unit = {
    children.put(name, ref) match {
      case null ⇒ // okay
      case old  ⇒ log.warning("{} replacing child {} ({} -> {})", path, name, old, ref)
    }
  }

  def removeChild(name: String): Unit = {
    children.remove(name) match {
      case null ⇒ log.warning("{} trying to remove non-child {}", path, name)
      case _    ⇒ //okay
    }
  }

  def getChild(name: String): InternalActorRef = children.get(name)

  override def getChild(name: Iterator[String]): InternalActorRef = {
    if (name.isEmpty) this
    else {
      val n = name.next()
      if (n.isEmpty) this
      else children.get(n) match {
        case null ⇒ Nobody
        case some ⇒
          if (name.isEmpty) some
          else some.getChild(name)
      }
    }
  }
}
