/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import scala.collection.immutable
import akka.dispatch._
import akka.dispatch.sysmsg._
import java.lang.{ UnsupportedOperationException, IllegalStateException }
import akka.serialization.{ Serialization, JavaSerializer }
import akka.event.EventStream
import scala.annotation.tailrec
import java.util.concurrent.ConcurrentHashMap
import akka.event.{ Logging, LoggingAdapter }
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

object ActorRef {

  /**
   * Use this value as an argument to [[ActorRef#tell]] if there is not actor to
   * reply to (e.g. when sending from non-actor code).
   */
  final val noSender: ActorRef = Actor.noSender

}

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
 * import scala.concurrent.Await
 *
 * class ExampleActor extends Actor {
 *   val other = context.actorOf(Props[OtherActor], "childName") // will be destroyed and re-created upon restart by default
 *
 *   def receive {
 *     case Request1(msg) => other ! refine(msg)     // uses this actor as sender reference, reply goes to us
 *     case Request2(msg) => other.tell(msg, sender()) // forward sender reference, enabling direct reply
 *     case Request3(msg) =>
 *       implicit val timeout = Timeout(5.seconds)
 *       (other ? msg) pipeTo sender()
 *       // the ask call will get a future from other's reply
 *       // when the future is complete, send its value to the original sender
 *   }
 * }
 * }}}
 *
 * Java:
 * {{{
 * import static akka.pattern.Patterns.ask;
 * import static akka.pattern.Patterns.pipe;
 *
 * public class ExampleActor extends UntypedActor {
 *   // this child will be destroyed and re-created upon restart by default
 *   final ActorRef other = getContext().actorOf(Props.create(OtherActor.class), "childName");
 *
 *   @Override
 *   public void onReceive(Object o) {
 *     if (o instanceof Request1) {
 *       Msg msg = ((Request1) o).getMsg();
 *       other.tell(msg, getSelf()); // uses this actor as sender reference, reply goes to us
 *
 *     } else if (o instanceof Request2) {
 *       Msg msg = ((Request2) o).getMsg();
 *       other.tell(msg, getSender()); // forward sender reference, enabling direct reply
 *
 *     } else if (o instanceof Request3) {
 *       Msg msg = ((Request3) o).getMsg();
 *       pipe(ask(other, msg, 5000), context().dispatcher()).to(getSender());
 *       // the ask call will get a future from other's reply
 *       // when the future is complete, send its value to the original sender
 *
 *     } else {
 *       unhandled(o);
 *     }
 *   }
 * }
 * }}}
 *
 * ActorRef does not have a method for terminating the actor it points to, use
 * [[akka.actor.ActorRefFactory]]`.stop(ref)`, or send a [[akka.actor.PoisonPill]],
 * for this purpose.
 *
 * Two actor references are compared equal when they have the same path and point to
 * the same actor incarnation. A reference pointing to a terminated actor doesn't compare
 * equal to a reference pointing to another (re-created) actor with the same path.
 *
 * If you need to keep track of actor references in a collection and do not care
 * about the exact actor incarnation you can use the ``ActorPath`` as key because
 * the unique id of the actor is not taken into account when comparing actor paths.
 */
abstract class ActorRef extends java.lang.Comparable[ActorRef] with Serializable {
  scalaRef: InternalActorRef ⇒

  /**
   * Returns the path for this actor (from this actor up to the root actor).
   */
  def path: ActorPath

  /**
   * Comparison takes path and the unique id of the actor cell into account.
   */
  final def compareTo(other: ActorRef) = {
    val x = this.path compareTo other.path
    if (x == 0) if (this.path.uid < other.path.uid) -1 else if (this.path.uid == other.path.uid) 0 else 1
    else x
  }

  /**
   * Sends the specified message to this ActorRef, i.e. fire-and-forget
   * semantics, including the sender reference if possible.
   *
   * Pass [[akka.actor.ActorRef]] `noSender` or `null` as sender if there is nobody to reply to
   */
  final def tell(msg: Any, sender: ActorRef): Unit = this.!(msg)(sender)

  /**
   * Forwards the message and passes the original sender actor as the sender.
   *
   * Works, no matter whether originally sent with tell/'!' or ask/'?'.
   */
  def forward(message: Any)(implicit context: ActorContext) = tell(message, context.sender())

  /**
   * INTERNAL API
   * Is the actor shut down?
   * The contract is that if this method returns true, then it will never be false again.
   * But you cannot rely on that it is alive if it returns false, since this by nature is a racy method.
   */
  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2")
  private[akka] def isTerminated: Boolean

  final override def hashCode: Int = {
    if (path.uid == ActorCell.undefinedUid) path.hashCode
    else path.uid
  }

  /**
   * Equals takes path and the unique id of the actor cell into account.
   */
  final override def equals(that: Any): Boolean = that match {
    case other: ActorRef ⇒ path.uid == other.path.uid && path == other.path
    case _               ⇒ false
  }

  override def toString: String =
    if (path.uid == ActorCell.undefinedUid) s"Actor[${path}]"
    else s"Actor[${path}#${path.uid}]"
}

/**
 * This trait represents the Scala Actor API
 * There are implicit conversions in ../actor/Implicits.scala
 * from ActorRef -&gt; ScalaActorRef and back
 */
trait ScalaActorRef { ref: ActorRef ⇒

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   *
   * If invoked from within an actor then the actor reference is implicitly passed on as the implicit 'sender' argument.
   * <p/>
   *
   * This actor 'sender' reference is then available in the receiving actor in the 'sender()' member variable,
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
  def start(): Unit
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

  /**
   * INTERNAL API: Returns “true” if the actor is locally known to be terminated, “false” if
   * alive or uncertain.
   */
  private[akka] def isTerminated: Boolean
}

/**
 * Common trait of all actor refs which actually have a Cell, most notably
 * LocalActorRef and RepointableActorRef. The former specializes the return
 * type of `underlying` so that follow-up calls can use invokevirtual instead
 * of invokeinterface.
 */
private[akka] abstract class ActorRefWithCell extends InternalActorRef { this: ActorRefScope ⇒
  def underlying: Cell
  def children: immutable.Iterable[ActorRef]
  def getSingleChild(name: String): InternalActorRef
}

/**
 * This is an internal look-up failure token, not useful for anything else.
 */
private[akka] case object Nobody extends MinimalActorRef {
  override val path: RootActorPath = new RootActorPath(Address("akka", "all-systems"), "/Nobody")
  override def provider = throw new UnsupportedOperationException("Nobody does not provide")

  private val serialized = new SerializedNobody

  @throws(classOf[java.io.ObjectStreamException])
  override protected def writeReplace(): AnyRef = serialized
}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L) private[akka] class SerializedNobody extends Serializable {
  @throws(classOf[java.io.ObjectStreamException])
  private def readResolve(): AnyRef = Nobody
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 *  INTERNAL API
 */
private[akka] class LocalActorRef private[akka] (
  _system:           ActorSystemImpl,
  _props:            Props,
  _dispatcher:       MessageDispatcher,
  _mailboxType:      MailboxType,
  _supervisor:       InternalActorRef,
  override val path: ActorPath)
  extends ActorRefWithCell with LocalRef {

  /*
   * Safe publication of this class’s fields is guaranteed by mailbox.setActor()
   * which is called indirectly from actorCell.init() (if you’re wondering why
   * this is at all important, remember that under the JMM final fields are only
   * frozen at the _end_ of the constructor, but we are publishing “this” before
   * that is reached).
   * This means that the result of newActorCell needs to be written to the val
   * actorCell before we call init and start, since we can start using "this"
   * object from another thread as soon as we run init.
   */
  private val actorCell: ActorCell = newActorCell(_system, this, _props, _dispatcher, _supervisor)
  actorCell.init(sendSupervise = true, _mailboxType)

  protected def newActorCell(system: ActorSystemImpl, ref: InternalActorRef, props: Props, dispatcher: MessageDispatcher, supervisor: InternalActorRef): ActorCell =
    new ActorCell(system, ref, props, dispatcher, supervisor)

  protected def actorContext: ActorContext = actorCell

  /**
   * INTERNAL API: Is the actor terminated?
   * If this method returns true, it will never return false again, but if it
   * returns false, you cannot be sure if it's alive still (race condition)
   */
  override private[akka] def isTerminated: Boolean = actorCell.isTerminated

  /**
   * Starts the actor after initialization.
   */
  override def start(): Unit = actorCell.start()

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

  def children: immutable.Iterable[ActorRef] = actorCell.children

  /**
   * Method for looking up a single child beneath this actor. Override in order
   * to inject “synthetic” actor paths like “/temp”.
   * It is racy if called from the outside.
   */
  def getSingleChild(name: String): InternalActorRef = actorCell.getSingleChild(name)

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

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = actorCell.sendMessage(message, sender)

  override def restart(cause: Throwable): Unit = actorCell.restart(cause)

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(this)
}

/**
 * Memento pattern for serializing ActorRefs transparently
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[akka] final case class SerializedActorRef private (path: String) {
  import akka.serialization.JavaSerializer.currentSystem

  def this(actorRef: ActorRef) = {
    this(Serialization.serializedActorPath(actorRef))
  }

  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = currentSystem.value match {
    case null ⇒
      throw new IllegalStateException(
        "Trying to deserialize a serialized ActorRef without an ActorSystem in scope." +
          " Use 'akka.serialization.Serialization.currentSystem.withValue(system) { ... }'")
    case someSystem ⇒
      someSystem.provider.resolveActorRef(path)
  }
}

/**
 * INTERNAL API
 */
private[akka] object SerializedActorRef {
  def apply(actorRef: ActorRef): SerializedActorRef = {
    new SerializedActorRef(actorRef)
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

  override def start(): Unit = ()
  override def suspend(): Unit = ()
  override def resume(causedByFailure: Throwable): Unit = ()
  override def stop(): Unit = ()
  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2")
  override private[akka] def isTerminated = false

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = ()

  override def sendSystemMessage(message: SystemMessage): Unit = ()
  override def restart(cause: Throwable): Unit = ()

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(this)
}

/** Subscribe to this class to be notified about all DeadLetters (also the suppressed ones). */
sealed trait AllDeadLetters {
  def message: Any
  def sender: ActorRef
  def recipient: ActorRef
}

/**
 * When a message is sent to an Actor that is terminated before receiving the message, it will be sent as a DeadLetter
 * to the ActorSystem's EventStream
 */
@SerialVersionUID(1L)
final case class DeadLetter(message: Any, sender: ActorRef, recipient: ActorRef) extends AllDeadLetters {
  require(sender ne null, "DeadLetter sender may not be null")
  require(recipient ne null, "DeadLetter recipient may not be null")
}

/**
 * Use with caution: Messages extending this trait will not be logged by the default dead-letters listener.
 * Instead they will be wrapped as [[SuppressedDeadLetter]] and may be subscribed for explicitly.
 */
trait DeadLetterSuppression

/**
 * Similar to [[DeadLetter]] with the slight twist of NOT being logged by the default dead letters listener.
 * Messages which end up being suppressed dead letters are internal messages for which ending up as dead-letter is both expected and harmless.
 *
 * It is possible to subscribe to suppressed dead letters on the ActorSystem's EventStream explicitly.
 */
@SerialVersionUID(1L)
final case class SuppressedDeadLetter(message: DeadLetterSuppression, sender: ActorRef, recipient: ActorRef) extends AllDeadLetters {
  require(sender ne null, "DeadLetter sender may not be null")
  require(recipient ne null, "DeadLetter recipient may not be null")
}

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
private[akka] class EmptyLocalActorRef(
  override val provider: ActorRefProvider,
  override val path:     ActorPath,
  val eventStream:       EventStream) extends MinimalActorRef {

  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2")
  override private[akka] def isTerminated = true

  override def sendSystemMessage(message: SystemMessage): Unit = {
    if (Mailbox.debug) println(s"ELAR $path having enqueued $message")
    specialHandle(message, provider.deadLetters)
  }

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = message match {
    case null ⇒ throw new InvalidMessageException("Message is null")
    case d: DeadLetter ⇒
      specialHandle(d.message, d.sender) // do NOT form endless loops, since deadLetters will resend!
    case _ if !specialHandle(message, sender) ⇒
      eventStream.publish(DeadLetter(message, if (sender eq Actor.noSender) provider.deadLetters else sender, this))
    case _ ⇒
  }

  protected def specialHandle(msg: Any, sender: ActorRef): Boolean = msg match {
    case w: Watch ⇒
      if (w.watchee == this && w.watcher != this)
        w.watcher.sendSystemMessage(
          DeathWatchNotification(w.watchee, existenceConfirmed = false, addressTerminated = false))
      true
    case _: Unwatch ⇒ true // Just ignore
    case Identify(messageId) ⇒
      sender ! ActorIdentity(messageId, None)
      true
    case sel: ActorSelectionMessage ⇒
      sel.identifyRequest match {
        case Some(identify) ⇒
          if (!sel.wildcardFanOut) sender ! ActorIdentity(identify.messageId, None)
        case None ⇒
          eventStream.publish(DeadLetter(sel.msg, if (sender eq Actor.noSender) provider.deadLetters else sender, this))
      }
      true
    case m: DeadLetterSuppression ⇒
      eventStream.publish(SuppressedDeadLetter(m, if (sender eq Actor.noSender) provider.deadLetters else sender, this))
      true
    case _ ⇒ false
  }
}

/**
 * Internal implementation of the dead letter destination: will publish any
 * received message to the eventStream, wrapped as [[akka.actor.DeadLetter]].
 *
 * INTERNAL API
 */
private[akka] class DeadLetterActorRef(
  _provider:    ActorRefProvider,
  _path:        ActorPath,
  _eventStream: EventStream) extends EmptyLocalActorRef(_provider, _path, _eventStream) {

  override def !(message: Any)(implicit sender: ActorRef = this): Unit = message match {
    case null                ⇒ throw new InvalidMessageException("Message is null")
    case Identify(messageId) ⇒ sender ! ActorIdentity(messageId, None)
    case d: DeadLetter       ⇒ if (!specialHandle(d.message, d.sender)) eventStream.publish(d)
    case _ ⇒ if (!specialHandle(message, sender))
      eventStream.publish(DeadLetter(message, if (sender eq Actor.noSender) provider.deadLetters else sender, this))
  }

  override protected def specialHandle(msg: Any, sender: ActorRef): Boolean = msg match {
    case w: Watch ⇒
      if (w.watchee != this && w.watcher != this)
        w.watcher.sendSystemMessage(
          DeathWatchNotification(w.watchee, existenceConfirmed = false, addressTerminated = false))
      true
    case _ ⇒ super.specialHandle(msg, sender)
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
  override val provider:  ActorRefProvider,
  override val path:      ActorPath,
  override val getParent: InternalActorRef,
  val log:                LoggingAdapter) extends MinimalActorRef {

  private val children = new ConcurrentHashMap[String, InternalActorRef]

  /**
   * In [[ActorSelectionMessage]]s only [[SelectChildName]] elements
   * are supported, otherwise messages are sent to [[EmptyLocalActorRef]].
   */
  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = message match {
    case sel @ ActorSelectionMessage(msg, elements, wildcardFanOut) ⇒ {
      require(elements.nonEmpty)

      def emptyRef = new EmptyLocalActorRef(provider, path / sel.elements.map(_.toString),
        provider.systemGuardian.underlying.system.eventStream)

      elements.head match {
        case SelectChildName(name) ⇒
          getChild(name) match {
            case null ⇒
              if (!wildcardFanOut)
                emptyRef.tell(msg, sender)
            case child ⇒
              if (elements.tail.isEmpty) {
                child ! msg
              } else if (!wildcardFanOut) {
                emptyRef.tell(msg, sender)
              }
          }
        case _ ⇒
          if (!wildcardFanOut)
            emptyRef.tell(msg, sender)
      }
    }
    case _ ⇒ super.!(message)
  }

  def addChild(name: String, ref: InternalActorRef): Unit = {
    children.put(name, ref) match {
      case null ⇒ // okay
      case old ⇒
        // this can happen from RemoteSystemDaemon if a new child is created
        // before the old is removed from RemoteSystemDaemon children
        log.debug("{} replacing child {} ({} -> {})", path, name, old, ref)
        old.stop()
    }
  }

  def removeChild(name: String): Unit =
    if (children.remove(name) eq null) log.warning("{} trying to remove non-child {}", path, name)

  /**
   * Remove a named child if it matches the ref.
   */
  protected def removeChild(name: String, ref: ActorRef): Unit = {
    val current = getChild(name)
    if (current eq null)
      log.warning("{} trying to remove non-child {}", path, name)
    else if (current == ref)
      children.remove(name, current) // remove when same value

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

  def hasChildren: Boolean = !children.isEmpty

  def foreachChild(f: ActorRef ⇒ Unit): Unit = {
    val iter = children.values.iterator
    while (iter.hasNext) f(iter.next)
  }
}

/**
 * INTERNAL API
 *
 * This kind of ActorRef passes all received messages to the given function for
 * performing a non-blocking side-effect. The intended use is to transform the
 * message before sending to the real target actor. Such references can be created
 * by calling `ActorCell.addFunctionRef` and must be deregistered when no longer
 * needed by calling `ActorCell.removeFunctionRef`. FunctionRefs do not count
 * towards the live children of an actor, they do not receive the Terminate command
 * and do not prevent the parent from terminating. FunctionRef is properly
 * registered for remote lookup and ActorSelection.
 *
 * When using the watch() feature you must ensure that upon reception of the
 * Terminated message the watched actorRef is unwatch()ed.
 */
private[akka] final class FunctionRef(
  override val path:     ActorPath,
  override val provider: ActorRefProvider,
  val eventStream:       EventStream,
  f:                     (ActorRef, Any) ⇒ Unit) extends MinimalActorRef {

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = {
    f(sender, message)
  }

  override def sendSystemMessage(message: SystemMessage): Unit = {
    message match {
      case w: Watch   ⇒ addWatcher(w.watchee, w.watcher)
      case u: Unwatch ⇒ remWatcher(u.watchee, u.watcher)
      case DeathWatchNotification(actorRef, _, _) ⇒
        this.!(Terminated(actorRef)(existenceConfirmed = true, addressTerminated = false))
      case _ ⇒ //ignore all other messages
    }
  }

  private[this] var watching = ActorCell.emptyActorRefSet
  private[this] val _watchedBy = new AtomicReference[Set[ActorRef]](ActorCell.emptyActorRefSet)

  override def isTerminated = _watchedBy.get() == null

  //noinspection EmptyCheck
  protected def sendTerminated(): Unit = {
    val watchedBy = _watchedBy.getAndSet(null)
    if (watchedBy != null) {
      if (watchedBy.nonEmpty) {
        watchedBy foreach sendTerminated(ifLocal = false)
        watchedBy foreach sendTerminated(ifLocal = true)
      }
      if (watching.nonEmpty) {
        watching foreach unwatchWatched
        watching = Set.empty
      }
    }
  }

  private def sendTerminated(ifLocal: Boolean)(watcher: ActorRef): Unit =
    if (watcher.asInstanceOf[ActorRefScope].isLocal == ifLocal)
      watcher.asInstanceOf[InternalActorRef].sendSystemMessage(DeathWatchNotification(this, existenceConfirmed = true, addressTerminated = false))

  private def unwatchWatched(watched: ActorRef): Unit =
    watched.asInstanceOf[InternalActorRef].sendSystemMessage(Unwatch(watched, this))

  override def stop(): Unit = sendTerminated()

  @tailrec private def addWatcher(watchee: ActorRef, watcher: ActorRef): Unit =
    _watchedBy.get() match {
      case null ⇒
        sendTerminated(ifLocal = true)(watcher)
        sendTerminated(ifLocal = false)(watcher)

      case watchedBy ⇒
        val watcheeSelf = watchee == this
        val watcherSelf = watcher == this

        if (watcheeSelf && !watcherSelf) {
          if (!watchedBy.contains(watcher))
            if (!_watchedBy.compareAndSet(watchedBy, watchedBy + watcher))
              addWatcher(watchee, watcher) // try again
        } else if (!watcheeSelf && watcherSelf) {
          publish(Logging.Warning(path.toString, classOf[FunctionRef], s"externally triggered watch from $watcher to $watchee is illegal on FunctionRef"))
        } else {
          publish(Logging.Error(path.toString, classOf[FunctionRef], s"BUG: illegal Watch($watchee,$watcher) for $this"))
        }
    }

  @tailrec private def remWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
    _watchedBy.get() match {
      case null ⇒ // do nothing...
      case watchedBy ⇒
        val watcheeSelf = watchee == this
        val watcherSelf = watcher == this

        if (watcheeSelf && !watcherSelf) {
          if (watchedBy.contains(watcher))
            if (!_watchedBy.compareAndSet(watchedBy, watchedBy - watcher))
              remWatcher(watchee, watcher) // try again
        } else if (!watcheeSelf && watcherSelf) {
          publish(Logging.Warning(path.toString, classOf[FunctionRef], s"externally triggered unwatch from $watcher to $watchee is illegal on FunctionRef"))
        } else {
          publish(Logging.Error(path.toString, classOf[FunctionRef], s"BUG: illegal Unwatch($watchee,$watcher) for $this"))
        }
    }
  }

  private def publish(e: Logging.LogEvent): Unit = try eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  /**
   * Have this FunctionRef watch the given Actor. This method must not be
   * called concurrently from different threads, it should only be called by
   * its parent Actor.
   *
   * Upon receiving the Terminated message, unwatch() must be called from a
   * safe context (i.e. normally from the parent Actor).
   */
  def watch(actorRef: ActorRef): Unit = {
    watching += actorRef
    actorRef.asInstanceOf[InternalActorRef].sendSystemMessage(Watch(actorRef.asInstanceOf[InternalActorRef], this))
  }

  /**
   * Have this FunctionRef unwatch the given Actor. This method must not be
   * called concurrently from different threads, it should only be called by
   * its parent Actor.
   */
  def unwatch(actorRef: ActorRef): Unit = {
    watching -= actorRef
    actorRef.asInstanceOf[InternalActorRef].sendSystemMessage(Unwatch(actorRef.asInstanceOf[InternalActorRef], this))
  }

  /**
   * Query whether this FunctionRef is currently watching the given Actor. This
   * method must not be called concurrently from different threads, it should
   * only be called by its parent Actor.
   */
  def isWatching(actorRef: ActorRef): Boolean = watching.contains(actorRef)
}
