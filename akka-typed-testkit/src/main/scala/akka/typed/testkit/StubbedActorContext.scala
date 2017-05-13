package akka.typed.testkit

import akka.{ actor ⇒ untyped }
import akka.typed._
import akka.util.Helpers

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration
import akka.annotation.InternalApi
import akka.typed.internal.ActorContextImpl

/**
 * An [[ActorContext]] for synchronous execution of a [[Behavior]] that
 * provides only stubs for the effects an Actor can perform and replaces
 * created child Actors by a synchronous Inbox (see `Inbox.sync`).
 *
 * See [[EffectfulActorContext]] for more advanced uses.
 */
class StubbedActorContext[T](
  val name:                     String,
  override val mailboxCapacity: Int,
  override val system:          ActorSystem[Nothing]) extends ActorContextImpl[T] {

  val selfInbox = Inbox[T](name)
  override val self = selfInbox.ref

  private var _children = TreeMap.empty[String, Inbox[_]]
  private val childName = Iterator from 1 map (Helpers.base64(_))

  override def children: Iterable[ActorRef[Nothing]] = _children.values map (_.ref)
  override def child(name: String): Option[ActorRef[Nothing]] = _children get name map (_.ref)
  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] = {
    val i = Inbox[U](childName.next())
    _children += i.ref.path.name → i
    i.ref
  }
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] =
    _children get name match {
      case Some(_) ⇒ throw untyped.InvalidActorNameException(s"actor name $name is already taken")
      case None ⇒
        // FIXME correct child path for the Inbox ref
        val i = Inbox[U](name)
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

  override def executionContext: ExecutionContextExecutor = system.executionContext

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def internalSpawnAdapter[U](f: U ⇒ T, name: String): ActorRef[U] = {
    val n = if (name != "") s"${childName.next()}-$name" else childName.next()
    val i = Inbox[U](n)
    _children += i.ref.path.name → i
    new internal.FunctionRef[U](
      self.path / i.ref.path.name,
      (msg, _) ⇒ { val m = f(msg); if (m != null) { selfInbox.ref ! m; i.ref ! msg } },
      (self) ⇒ selfInbox.ref.sorry.sendSystem(internal.DeathWatchNotification(self, null)))
  }

  /**
   * Retrieve the inbox representing the given child actor. The passed ActorRef must be one that was returned
   * by one of the spawn methods earlier.
   */
  def childInbox[U](child: ActorRef[U]): Inbox[U] = {
    val inbox = _children(child.path.name).asInstanceOf[Inbox[U]]
    if (inbox.ref != child) throw new IllegalArgumentException(s"$child is not a child of $this")
    inbox
  }

  /**
   * Retrieve the inbox representing the child actor with the given name.
   */
  def childInbox[U](name: String): Inbox[U] = _children(name).asInstanceOf[Inbox[U]]

  /**
   * Remove the given inbox from the list of children, for example after
   * having simulated its termination.
   */
  def removeChildInbox(child: ActorRef[Nothing]): Unit = _children -= child.path.name

  override def toString: String = s"Inbox($self)"
}
