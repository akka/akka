/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.duration.Duration
import scala.collection.immutable.TreeMap
import akka.util.Helpers
import akka.{ actor ⇒ untyped }
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContextExecutor
import java.util.Optional
import java.util.ArrayList
import akka.annotation.DoNotInherit
import akka.annotation.ApiMayChange

/**
 * This trait is not meant to be extended by user code. If you do so, you may
 * lose binary compatibility.
 */
@DoNotInherit
@ApiMayChange
trait ActorContext[T] extends javadsl.ActorContext[T] with scaladsl.ActorContext[T] {
  override def getChild(name: String): Optional[ActorRef[Void]] =
    child(name) match {
      case Some(c) ⇒ Optional.of(c.upcast[Void])
      case None    ⇒ Optional.empty()
    }

  override def getChildren(): java.util.List[akka.typed.ActorRef[Void]] = {
    val c = children
    val a = new ArrayList[ActorRef[Void]](c.size)
    val i = c.iterator
    while (i.hasNext) a.add(i.next().upcast[Void])
    a
  }

  override def getExecutionContext(): scala.concurrent.ExecutionContextExecutor =
    executionContext

  override def getMailboxCapacity(): Int =
    mailboxCapacity

  override def getSelf(): akka.typed.ActorRef[T] =
    self

  override def getSystem(): akka.typed.ActorSystem[Void] =
    system.asInstanceOf[ActorSystem[Void]]

  override def spawn[U](behavior: akka.typed.Behavior[U], name: String): akka.typed.ActorRef[U] =
    spawn(behavior, name, EmptyDeploymentConfig)

  override def spawnAnonymous[U](behavior: akka.typed.Behavior[U]): akka.typed.ActorRef[U] =
    spawnAnonymous(behavior, EmptyDeploymentConfig)

  override def createAdapter[U](f: java.util.function.Function[U, T]): akka.typed.ActorRef[U] =
    spawnAdapter(f.apply _)

  override def createAdapter[U](f: java.util.function.Function[U, T], name: String): akka.typed.ActorRef[U] =
    spawnAdapter(f.apply _, name)
}

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
  override val system:          ActorSystem[Nothing]) extends ActorContext[T] {

  val selfInbox = Inbox[T](name)
  override val self = selfInbox.ref

  private var _children = TreeMap.empty[String, Inbox[_]]
  private val childName = Iterator from 1 map (Helpers.base64(_))

  override def children: Iterable[ActorRef[Nothing]] = _children.values map (_.ref)
  override def child(name: String): Option[ActorRef[Nothing]] = _children get name map (_.ref)
  override def spawnAnonymous[U](behavior: Behavior[U], deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[U] = {
    val i = Inbox[U](childName.next())
    _children += i.ref.path.name → i
    i.ref
  }
  override def spawn[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[U] =
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
  override def stop(child: ActorRef[_]): Boolean = {
    _children.get(child.path.name) match {
      case None        ⇒ false
      case Some(inbox) ⇒ inbox.ref == child
    }
  }
  override def watch[U](other: ActorRef[U]): ActorRef[U] = other
  override def unwatch[U](other: ActorRef[U]): ActorRef[U] = other
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = ()
  override def cancelReceiveTimeout(): Unit = ()

  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): untyped.Cancellable = new untyped.Cancellable {
    override def cancel() = false
    override def isCancelled = true
  }

  override def executionContext: ExecutionContextExecutor = system.executionContext

  override def spawnAdapter[U](f: U ⇒ T, name: String = ""): ActorRef[U] = {
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
