/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.util._
import java.lang.{ UnsupportedOperationException, IllegalStateException }
import akka.serialization.{ Serialization, JavaSerializer }
import akka.event.EventStream
import scala.annotation.tailrec
import java.util.concurrent.ConcurrentHashMap
import akka.event.LoggingAdapter
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.collection.JavaConverters

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
 * import akka.pattern.ask
 *
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
 * import static akka.pattern.Patterns.ask;
 *
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
 *       getSender().tell(ask(other, msg, 5000)); // reply with Future for holding the other’s reply (timeout 5 seconds)
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
  @deprecated("use the two-arg variant (typically getSelf() as second arg)", "2.1")
  final def tell(msg: Any): Unit = this.!(msg)(null: ActorRef)

  /**
   * Java API. <p/>
   * Sends the specified message to the sender, i.e. fire-and-forget
   * semantics, including the sender reference if possible (pass `null` if
   * there is nobody to reply to).<p/>
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
  def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit

}

/**
 * All ActorRefs have a scope which describes where they live. Since it is
 * often necessary to distinguish between local and non-local references, this
 * is the only method provided on the scope.
 */
private[akka] trait ActorRefScope {
  def isLocal: Boolean
}

/**
 * Refs which are statically known to be local inherit from this Scope
 */
private[akka] trait LocalRef extends ActorRefScope {
  final def isLocal = true
}

/**
 * RepointableActorRef (and potentially others) may change their locality at
 * runtime, meaning that isLocal might not be stable. RepointableActorRef has
 * the feature that it starts out “not fully started” (but you can send to it),
 * which is why `isStarted` features here; it is not improbable that cluster
 * actor refs will have the same behavior.
 */
private[akka] trait RepointableRef extends ActorRefScope {
  def isStarted: Boolean
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
  def start(): ActorRef
  def resume(causedByFailure: Throwable): Unit
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
 * Common trait of all actor refs which actually have a Cell, most notably
 * LocalActorRef and RepointableActorRef. The former specializes the return
 * type of `underlying` so that follow-up calls can use invokevirtual instead
 * of invokeinterface.
 */
private[akka] abstract class ActorRefWithCell extends InternalActorRef { this: ActorRefScope ⇒
  def underlying: Cell
}

/**
 * This is an internal look-up failure token, not useful for anything else.
 */
private[akka] case object Nobody extends MinimalActorRef {
  override val path: RootActorPath = new RootActorPath(Address("akka", "all-systems"), "/Nobody")
  override def provider = throw new UnsupportedOperationException("Nobody does not provide")
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 *  INTERNAL API
 */
private[akka] class LocalActorRef private[akka] (
  _system: ActorSystemImpl,
  _props: Props,
  _supervisor: InternalActorRef,
  override val path: ActorPath)
  extends ActorRefWithCell with LocalRef {

  /*
   * Safe publication of this class’s fields is guaranteed by mailbox.setActor()
   * which is called indirectly from actorCell.start() (if you’re wondering why
   * this is at all important, remember that under the JMM final fields are only
   * frozen at the _end_ of the constructor, but we are publishing “this” before
   * that is reached).
   */
  private val actorCell: ActorCell = newActorCell(_system, this, _props, _supervisor)
  actorCell.init(ThreadLocalRandom.current.nextInt(), sendSupervise = true)
  if (actorCellShouldStart)
    actorCell.start()

  protected def newActorCell(system: ActorSystemImpl, ref: InternalActorRef, props: Props, supervisor: InternalActorRef): ActorCell =
    new ActorCell(system, ref, props, supervisor)

  protected def actorCellShouldStart(): Boolean = false

  protected def actorContext: ActorContext = actorCell

  /**
   * Is the actor terminated?
   * If this method returns true, it will never return false again, but if it
   * returns false, you cannot be sure if it's alive still (race condition)
   */
  override def isTerminated: Boolean = actorCell.isTerminated

  override def start(): ActorRef = {
    actorCell.start()
    this
  }

  /**
   * Suspends the actor so that it will not process messages until resumed. The
   * suspend request is processed asynchronously to the caller of this method
   * as well as to normal message sends: the only ordering guarantee is that
   * message sends done from the same thread after calling this method will not
   * be processed until resumed.
   */
  override def suspend(): Unit = actorCell.suspend()

  /**
   * Resumes a suspended actor.
   */
  override def resume(causedByFailure: Throwable): Unit = actorCell.resume(causedByFailure)

  /**
   * Shuts down the actor and its message queue
   */
  override def stop(): Unit = actorCell.stop()

  override def getParent: InternalActorRef = actorCell.parent

  override def provider: ActorRefProvider = actorCell.provider

  /**
   * Method for looking up a single child beneath this actor. Override in order
   * to inject “synthetic” actor paths like “/temp”.
   */
  protected def getSingleChild(name: String): InternalActorRef =
    actorCell.getChildByName(name) match {
      case Some(crs: ChildRestartStats) ⇒ crs.child.asInstanceOf[InternalActorRef]
      case _                            ⇒ Nobody
    }

  override def getChild(names: Iterator[String]): InternalActorRef = {
    /*
     * The idea is to recursively descend as far as possible with LocalActor
     * Refs and hand over to that “foreign” child when we encounter it.
     */
    @tailrec
    def rec(ref: InternalActorRef, name: Iterator[String]): InternalActorRef =
      ref match {
        case l: LocalActorRef ⇒
          val next = name.next() match {
            case ".." ⇒ l.getParent
            case ""   ⇒ l
            case any  ⇒ l.getSingleChild(any)
          }
          if (next == Nobody || name.isEmpty) next else rec(next, name)
        case _ ⇒
          ref.getChild(name)
      }

    if (names.isEmpty) this
    else rec(this, names)
  }

  // ========= AKKA PROTECTED FUNCTIONS =========

  def underlying: ActorCell = actorCell

  override def sendSystemMessage(message: SystemMessage): Unit = actorCell.sendSystemMessage(message)

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = actorCell.tell(message, sender)

  override def restart(cause: Throwable): Unit = actorCell.restart(cause)

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(path)
}

/**
 * Memento pattern for serializing ActorRefs transparently
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[akka] case class SerializedActorRef private (path: String) {
  import akka.serialization.JavaSerializer.currentSystem

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

/**
 * INTERNAL API
 */
private[akka] object SerializedActorRef {
  def apply(path: ActorPath): SerializedActorRef = {
    Serialization.currentTransportAddress.value match {
      case null ⇒ new SerializedActorRef(path.toString)
      case addr ⇒ new SerializedActorRef(path.toStringWithAddress(addr))
    }
  }
}

/**
 * Trait for ActorRef implementations where all methods contain default stubs.
 *
 * INTERNAL API
 */
private[akka] trait MinimalActorRef extends InternalActorRef with LocalRef {

  override def getParent: InternalActorRef = Nobody
  override def getChild(names: Iterator[String]): InternalActorRef = if (names.forall(_.isEmpty)) this else Nobody

  override def start(): ActorRef = this
  override def suspend(): Unit = ()
  override def resume(causedByFailure: Throwable): Unit = ()
  override def stop(): Unit = ()
  override def isTerminated = false

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = ()

  override def sendSystemMessage(message: SystemMessage): Unit = ()
  override def restart(cause: Throwable): Unit = ()

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(path)
}

/**
 * When a message is sent to an Actor that is terminated before receiving the message, it will be sent as a DeadLetter
 * to the ActorSystem's EventStream
 */
@SerialVersionUID(1L)
case class DeadLetter(message: Any, sender: ActorRef, recipient: ActorRef)

private[akka] object DeadLetterActorRef {
  @SerialVersionUID(1L)
  class SerializedDeadLetterActorRef extends Serializable { //TODO implement as Protobuf for performance?
    @throws(classOf[java.io.ObjectStreamException])
    private def readResolve(): AnyRef = JavaSerializer.currentSystem.value.deadLetters
  }

  val serialized = new SerializedDeadLetterActorRef
}

/**
 * This special dead letter reference has a name: it is that which is returned
 * by a local look-up which is unsuccessful.
 *
 * INTERNAL API
 */
private[akka] class EmptyLocalActorRef(override val provider: ActorRefProvider,
                                       override val path: ActorPath,
                                       val eventStream: EventStream) extends MinimalActorRef {

  override def isTerminated(): Boolean = true

  override def sendSystemMessage(message: SystemMessage): Unit = specialHandle(message)

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = message match {
    case d: DeadLetter ⇒ specialHandle(d.message) // do NOT form endless loops, since deadLetters will resend!
    case _             ⇒ if (!specialHandle(message)) eventStream.publish(DeadLetter(message, sender, this))
  }

  protected def specialHandle(msg: Any): Boolean = msg match {
    case w: Watch ⇒
      if (w.watchee == this && w.watcher != this)
        w.watcher ! Terminated(w.watchee)(existenceConfirmed = false, addressTerminated = false)
      true
    case _: Unwatch ⇒ true // Just ignore
    case _          ⇒ false
  }
}

/**
 * Internal implementation of the dead letter destination: will publish any
 * received message to the eventStream, wrapped as [[akka.actor.DeadLetter]].
 *
 * INTERNAL API
 */
private[akka] class DeadLetterActorRef(_provider: ActorRefProvider,
                                       _path: ActorPath,
                                       _eventStream: EventStream) extends EmptyLocalActorRef(_provider, _path, _eventStream) {

  override def !(message: Any)(implicit sender: ActorRef = this): Unit = message match {
    case d: DeadLetter ⇒ if (!specialHandle(d.message)) eventStream.publish(d)
    case _             ⇒ if (!specialHandle(message)) eventStream.publish(DeadLetter(message, sender, this))
  }

  override protected def specialHandle(msg: Any): Boolean = msg match {
    case w: Watch ⇒
      if (w.watchee != this && w.watcher != this)
        w.watcher ! Terminated(w.watchee)(existenceConfirmed = false, addressTerminated = false)
      true
    case w: Unwatch  ⇒ true // Just ignore
    case NullMessage ⇒ true
    case _           ⇒ false
  }

  @throws(classOf[java.io.ObjectStreamException])
  override protected def writeReplace(): AnyRef = DeadLetterActorRef.serialized
}

/**
 * Internal implementation detail used for paths like “/temp”
 *
 * INTERNAL API
 */
private[akka] class VirtualPathContainer(
  override val provider: ActorRefProvider,
  override val path: ActorPath,
  override val getParent: InternalActorRef,
  val log: LoggingAdapter) extends MinimalActorRef {

  private val children = new ConcurrentHashMap[String, InternalActorRef]

  def addChild(name: String, ref: InternalActorRef): Unit = {
    children.put(name, ref) match {
      case null ⇒ // okay
      case old  ⇒ log.warning("{} replacing child {} ({} -> {})", path, name, old, ref)
    }
  }

  def removeChild(name: String): Unit =
    if (children.remove(name) eq null) log.warning("{} trying to remove non-child {}", path, name)

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

  def hasChildren: Boolean = !children.isEmpty

  def foreachChild(f: ActorRef ⇒ Unit) = {
    val iter = children.values.iterator
    while (iter.hasNext) f(iter.next)
  }
}
