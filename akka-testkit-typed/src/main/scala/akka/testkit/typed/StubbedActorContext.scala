package akka.testkit.typed

import akka.actor.InvalidMessageException
import akka.{ actor ⇒ untyped }
import akka.actor.typed._
import akka.util.Helpers
import akka.{ actor ⇒ a }

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration
import akka.annotation.InternalApi
import akka.actor.typed.internal.{ ActorContextImpl, ActorRefImpl, ActorSystemStub, SystemMessage }
import akka.event.Logging.{ Info, LogEvent, LogLevel }
import akka.event.{ Logging, LoggingAdapter }

/**
 * A local synchronous ActorRef that invokes the given function for every message send.
 * This reference cannot watch other references.
 */
private[akka] final class FunctionRef[-T](
  _path:      a.ActorPath,
  send:       (T, FunctionRef[T]) ⇒ Unit,
  _terminate: FunctionRef[T] ⇒ Unit)
  extends ActorRef[T] with ActorRefImpl[T] {

  override def tell(msg: T): Unit = {
    if (msg == null) throw InvalidMessageException("[null] is not an allowed message")
    send(msg, this)
  }

  override def path = _path
  override def sendSystem(signal: SystemMessage): Unit = {}
  override def isLocal = true
}

/**
 * INTERNAL API
 *
 * Captures log events for test inspection
 */
@InternalApi private[akka] final class StubbedLoggingAdapter extends LoggingAdapter {

  private var logBuffer: List[(LogLevel, String)] = Nil

  def isErrorEnabled: Boolean = true
  def isWarningEnabled: Boolean = true
  def isInfoEnabled: Boolean = true
  def isDebugEnabled: Boolean = true

  protected def notifyError(message: String): Unit =
    logBuffer = (Logging.ErrorLevel, message) :: logBuffer
  protected def notifyError(cause: Throwable, message: String): Unit =
    logBuffer = (Logging.ErrorLevel, message) :: logBuffer

  protected def notifyWarning(message: String): Unit =
    logBuffer = (Logging.WarningLevel, message) :: logBuffer

  protected def notifyInfo(message: String): Unit =
    logBuffer = (Logging.InfoLevel, message) :: logBuffer

  protected def notifyDebug(message: String): Unit =
    logBuffer = (Logging.DebugLevel, message) :: logBuffer

  def logEntries: List[(LogLevel, String)] = logBuffer.reverse
  def clearLog(): Unit = logBuffer = Nil

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
  private val loggingAdapter = new StubbedLoggingAdapter

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
  @InternalApi private[akka] def internalSpawnMessageAdapter[U](f: U ⇒ T, name: String): ActorRef[U] = {

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

  override def log: LoggingAdapter = loggingAdapter

  /**
   * The log entries logged through ctx.log.{debug, info, warn, error} are captured and can be verified through
   * this method.
   */
  def logEntries: List[(LogLevel, String)] = loggingAdapter.logEntries

  /**
   * Clear the log entries
   */
  def clearLog(): Unit = loggingAdapter.clearLog()
}
