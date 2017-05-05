/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.{ actor ⇒ a }
import akka.dispatch.sysmsg._
import akka.util.Unsafe.{ instance ⇒ unsafe }
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.Future
import java.util.ArrayList
import scala.util.{ Success, Failure }
import scala.annotation.unchecked.uncheckedVariance

/**
 * Every ActorRef is also an ActorRefImpl, but these two methods shall be
 * completely hidden from client code. There is an implicit converter
 * available in the package object, enabling `ref.toImpl` (or `ref.toImplN`
 * for `ActorRef[Nothing]`—Scala refuses to infer `Nothing` as a type parameter).
 */
private[typed] trait ActorRefImpl[-T] extends ActorRef[T] {
  def sendSystem(signal: SystemMessage): Unit
  def isLocal: Boolean

  final override def narrow[U <: T]: ActorRef[U] = this.asInstanceOf[ActorRef[U]]

  final override def upcast[U >: T @uncheckedVariance]: ActorRef[U] = this.asInstanceOf[ActorRef[U]]

  /**
   * Comparison takes path and the unique id of the actor cell into account.
   */
  final override def compareTo(other: ActorRef[_]) = {
    val x = this.path compareTo other.path
    if (x == 0) if (this.path.uid < other.path.uid) -1 else if (this.path.uid == other.path.uid) 0 else 1
    else x
  }

  final override def hashCode: Int = path.uid

  /**
   * Equals takes path and the unique id of the actor cell into account.
   */
  final override def equals(that: Any): Boolean = that match {
    case other: ActorRef[_] ⇒ path.uid == other.path.uid && path == other.path
    case _                  ⇒ false
  }

  override def toString: String = s"Actor[${path}#${path.uid}]"
}

/**
 * A local ActorRef that is backed by an asynchronous [[ActorCell]].
 */
private[typed] class LocalActorRef[-T](override val path: a.ActorPath, cell: ActorCell[T])
  extends ActorRef[T] with ActorRefImpl[T] {
  override def tell(msg: T): Unit = cell.send(msg)
  override def sendSystem(signal: SystemMessage): Unit = cell.sendSystem(signal)
  final override def isLocal: Boolean = true
  private[typed] def getCell: ActorCell[_] = cell
}

/**
 * A local ActorRef that just discards everything that is sent to it. This
 * implies that it effectively has an infinite lifecycle, i.e. it never
 * terminates (meaning: no Hawking radiation).
 */
private[typed] object BlackholeActorRef
  extends ActorRef[Any] with ActorRefImpl[Any] {
  override val path: a.ActorPath = a.RootActorPath(a.Address("akka.typed.internal", "blackhole"))
  override def tell(msg: Any): Unit = ()
  override def sendSystem(signal: SystemMessage): Unit = ()
  final override def isLocal: Boolean = true
}

/**
 * A local synchronous ActorRef that invokes the given function for every message send.
 * This reference can be watched and will do the right thing when it receives a [[DeathWatchNotification]].
 * This reference cannot watch other references.
 */
private[typed] final class FunctionRef[-T](
  _path:      a.ActorPath,
  send:       (T, FunctionRef[T]) ⇒ Unit,
  _terminate: FunctionRef[T] ⇒ Unit)
  extends WatchableRef[T](_path) {

  override def tell(msg: T): Unit =
    if (isAlive)
      try send(msg, this) catch {
        case NonFatal(ex) ⇒ // nothing we can do here
      }
    else () // we don’t have deadLetters available

  override def sendSystem(signal: SystemMessage): Unit = signal match {
    case Create()                           ⇒ // nothing to do
    case DeathWatchNotification(ref, cause) ⇒ // we’re not watching, and we’re not a parent either
    case Terminate()                        ⇒ doTerminate()
    case Watch(watchee, watcher)            ⇒ if (watchee == this && watcher != this) addWatcher(watcher.sorryForNothing)
    case Unwatch(watchee, watcher)          ⇒ if (watchee == this && watcher != this) remWatcher(watcher.sorryForNothing)
    case NoMessage                          ⇒ // nothing to do
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
    watcher.sendSystem(DeathWatchNotification(this, null))

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
 * A Future of an ActorRef can quite easily be wrapped as an ActorRef since no
 * promises are made about delivery delays: as long as the Future is not ready
 * messages will be queued, afterwards they get sent without waiting.
 */
private[typed] class FutureRef[-T](_path: a.ActorPath, bufferSize: Int, f: Future[ActorRef[T]]) extends WatchableRef[T](_path) {
  import FutureRef._

  // Keep in synch with `targetOffset` in companion (could also change on mixing in a trait).
  @volatile private[this] var _target: Either[ArrayList[T], ActorRef[T]] = Left(new ArrayList[T])

  f.onComplete {
    case Success(ref) ⇒
      _target match {
        case l @ Left(list) ⇒
          list.synchronized {
            val it = list.iterator
            while (it.hasNext) ref ! it.next()
            if (unsafe.compareAndSwapObject(this, targetOffset, l, Right(ref)))
              ref.sorry.sendSystem(Watch(ref, this))
            // if this fails, concurrent termination has won and there is no point in watching
          }
        case _ ⇒ // already terminated
      }
    case Failure(ex) ⇒ doTerminate()
  }(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)

  override def terminate(): Unit = {
    val old = unsafe.getAndSetObject(this, targetOffset, Right(BlackholeActorRef))
    old match {
      case Right(target: ActorRef[_]) ⇒ target.sorry.sendSystem(Unwatch(target, this))
      case _                          ⇒ // nothing to do
    }
  }

  override def tell(msg: T): Unit =
    _target match {
      case Left(list) ⇒
        list.synchronized {
          if (_target.isRight) tell(msg)
          else if (list.size < bufferSize) list.add(msg)
        }
      case Right(ref) ⇒ ref ! msg
    }

  override def sendSystem(signal: SystemMessage): Unit = signal match {
    case Create() ⇒ // nothing to do
    case DeathWatchNotification(ref, cause) ⇒
      _target = Right(BlackholeActorRef) // avoid sending Unwatch() in this case
      doTerminate() // this can only be the result of watching the target
    case Terminate()               ⇒ doTerminate()
    case Watch(watchee, watcher)   ⇒ if (watchee == this && watcher != this) addWatcher(watcher.sorryForNothing)
    case Unwatch(watchee, watcher) ⇒ if (watchee == this && watcher != this) remWatcher(watcher.sorryForNothing)
    case NoMessage                 ⇒ // nothing to do
  }

  override def isLocal = true
}

private[typed] object FutureRef {
  val targetOffset = {
    val fields = classOf[FutureRef[_]].getDeclaredFields.toList
    // On Scala 2.12, the field's name is exactly "_target" (and it's private), earlier Scala versions compile the val to a public field that's name mangled to "akka$typed$internal$FutureRef$$_target"
    val targetField = fields.find(_.getName.endsWith("_target"))
    assert(targetField.nonEmpty, s"Could not find _target field in FutureRef class among fields $fields.")

    unsafe.objectFieldOffset(targetField.get)
  }
}
