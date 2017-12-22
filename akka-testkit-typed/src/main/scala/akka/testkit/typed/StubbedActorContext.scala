package akka.testkit.typed

import akka.actor.InvalidMessageException
import akka.{ actor ⇒ untyped }
import akka.actor.typed._
import akka.util.Helpers
import akka.{ actor ⇒ a }
import akka.util.Unsafe.{ instance ⇒ unsafe }

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration
import akka.annotation.InternalApi
import akka.actor.typed.internal.{ ActorContextImpl, ActorRefImpl, ActorSystemStub }

import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * A local synchronous ActorRef that invokes the given function for every message send.
 * This reference can be watched and will do the right thing when it receives a [[akka.actor.typed.internal.DeathWatchNotification]].
 * This reference cannot watch other references.
 */
private[akka] final class FunctionRef[-T](
  _path:      a.ActorPath,
  send:       (T, FunctionRef[T]) ⇒ Unit,
  _terminate: FunctionRef[T] ⇒ Unit)
  extends WatchableRef[T](_path) {

  override def tell(msg: T): Unit = {
    if (msg == null) throw InvalidMessageException("[null] is not an allowed message")
    if (isAlive)
      try send(msg, this) catch {
        case NonFatal(_) ⇒ // nothing we can do here
      }
    else () // we don’t have deadLetters available
  }

  import internal._

  override def sendSystem(signal: SystemMessage): Unit = signal match {
    case internal.Create()                     ⇒ // nothing to do
    case internal.DeathWatchNotification(_, _) ⇒ // we’re not watching, and we’re not a parent either
    case internal.Terminate()                  ⇒ doTerminate()
    case internal.Watch(watchee, watcher)      ⇒ if (watchee == this && watcher != this) addWatcher(watcher.sorryForNothing)
    case internal.Unwatch(watchee, watcher)    ⇒ if (watchee == this && watcher != this) remWatcher(watcher.sorryForNothing)
    case NoMessage                             ⇒ // nothing to do
  }

  override def isLocal = true

  override def terminate(): Unit = _terminate(this)
}

/**
 * The mechanics for synthetic ActorRefs that have a lifecycle and support being watched.
 */
private[typed] abstract class WatchableRef[-T](override val path: a.ActorPath) extends ActorRef[T] with ActorRefImpl[T] {
  import WatchableRef._

  /**
   * Callback that is invoked when this ref has terminated. Even if doTerminate() is
   * called multiple times, this callback is invoked only once.
   */
  protected def terminate(): Unit

  type S = Set[ActorRefImpl[Nothing]]

  @volatile private[this] var _watchedBy: S = Set.empty

  protected def isAlive: Boolean = _watchedBy != null

  protected def doTerminate(): Unit = {
    val watchedBy = unsafe.getAndSetObject(this, watchedByOffset, null).asInstanceOf[S]
    if (watchedBy != null) {
      try terminate() catch { case NonFatal(ex) ⇒ }
      if (watchedBy.nonEmpty) watchedBy foreach sendTerminated
    }
  }

  private def sendTerminated(watcher: ActorRefImpl[Nothing]): Unit =
    watcher.sendSystem(internal.DeathWatchNotification(this, null))

  @tailrec final protected def addWatcher(watcher: ActorRefImpl[Nothing]): Unit =
    _watchedBy match {
      case null ⇒ sendTerminated(watcher)
      case watchedBy ⇒
        if (!watchedBy.contains(watcher))
          if (!unsafe.compareAndSwapObject(this, watchedByOffset, watchedBy, watchedBy + watcher))
            addWatcher(watcher) // try again
    }

  @tailrec final protected def remWatcher(watcher: ActorRefImpl[Nothing]): Unit = {
    _watchedBy match {
      case null ⇒ // do nothing...
      case watchedBy ⇒
        if (watchedBy.contains(watcher))
          if (!unsafe.compareAndSwapObject(this, watchedByOffset, watchedBy, watchedBy - watcher))
            remWatcher(watcher) // try again
    }
  }
}

private[typed] object WatchableRef {
  val watchedByOffset = unsafe.objectFieldOffset(classOf[WatchableRef[_]].getDeclaredField("_watchedBy"))
}

/**
 * INTERNAL API
 *
 * An [[ActorContext]] for synchronous execution of a [[Behavior]] that
 * provides only stubs for the effects an Actor can perform and replaces
 * created child Actors by a synchronous Inbox (see `Inbox.sync`).
 *
 * See [[BehaviorTestkit]] for more advanced uses.
 */
@InternalApi private[akka] class StubbedActorContext[T](
  val name: String) extends ActorContextImpl[T] {

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val selfInbox = TestInbox[T](name)

  override val self = selfInbox.ref
  override val system = new ActorSystemStub("StubbedActorContext")
  // Not used for a stubbed actor context
  override def mailboxCapacity = 1

  private var _children = TreeMap.empty[String, TestInbox[_]]
  private val childName = Iterator from 0 map (Helpers.base64(_))

  override def children: Iterable[ActorRef[Nothing]] = _children.values map (_.ref)
  def childrenNames: Iterable[String] = _children.keys

  override def child(name: String): Option[ActorRef[Nothing]] = _children get name map (_.ref)

  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] = {
    val i = TestInbox[U](childName.next())
    _children += i.ref.path.name → i
    i.ref
  }
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] =
    _children get name match {
      case Some(_) ⇒ throw untyped.InvalidActorNameException(s"actor name $name is already taken")
      case None ⇒
        // FIXME correct child path for the Inbox ref
        val i = TestInbox[U](name)
        _children += name → i
        i.ref
    }

  /**
   * Do not actually stop the child inbox, only simulate the liveness check.
   * Removal is asynchronous, explicit removeInbox is needed from outside afterwards.
   */
  override def stop[U](child: ActorRef[U]): Boolean = {
    _children.get(child.path.name) match {
      case None        ⇒ false
      case Some(inbox) ⇒ inbox.ref == child
    }
  }
  override def watch[U](other: ActorRef[U]): Unit = ()
  override def watchWith[U](other: ActorRef[U], msg: T): Unit = ()
  override def unwatch[U](other: ActorRef[U]): Unit = ()
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = ()
  override def cancelReceiveTimeout(): Unit = ()

  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): untyped.Cancellable = new untyped.Cancellable {
    override def cancel() = false
    override def isCancelled = true
  }

  // TODO allow overriding of this
  override def executionContext: ExecutionContextExecutor = system.executionContext

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def internalSpawnAdapter[U](f: U ⇒ T, name: String): ActorRef[U] = {

    val n = if (name != "") s"${childName.next()}-$name" else childName.next()
    val i = TestInbox[U](n)
    _children += i.ref.path.name → i

    new FunctionRef[U](
      self.path / i.ref.path.name,
      (msg, _) ⇒ { val m = f(msg); if (m != null) { selfInbox.ref ! m; i.ref ! msg } },
      (self) ⇒ selfInbox.ref.sorry.sendSystem(internal.DeathWatchNotification(self, null)))
  }

  /**
   * Retrieve the inbox representing the given child actor. The passed ActorRef must be one that was returned
   * by one of the spawn methods earlier.
   */
  def childInbox[U](child: ActorRef[U]): TestInbox[U] = {
    val inbox = _children(child.path.name).asInstanceOf[TestInbox[U]]
    if (inbox.ref != child) throw new IllegalArgumentException(s"$child is not a child of $this")
    inbox
  }

  /**
   * Retrieve the inbox representing the child actor with the given name.
   */
  def childInbox[U](name: String): Option[TestInbox[U]] = _children.get(name).map(_.asInstanceOf[TestInbox[U]])

  /**
   * Remove the given inbox from the list of children, for example after
   * having simulated its termination.
   */
  def removeChildInbox(child: ActorRef[Nothing]): Unit = _children -= child.path.name

  override def toString: String = s"Inbox($self)"
}
