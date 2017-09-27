/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.testkit

import java.util.concurrent.ConcurrentLinkedQueue

import akka.typed.{ ActorContext, ActorRef, ActorSystem, Behavior, PostStop, Props, Signal }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * All tracked effects must extend implement this type. It is deliberately
 * not sealed in order to allow extensions.
 */
abstract class Effect

object Effect {

  abstract class SpawnedEffect extends Effect

  @SerialVersionUID(1L) final case class Spawned(childName: String, props: Props) extends SpawnedEffect
  @SerialVersionUID(1L) final case class SpawnedAnonymous(props: Props) extends SpawnedEffect
  @SerialVersionUID(1L) final case object SpawnedAdapter extends SpawnedEffect
  @SerialVersionUID(1L) final case class Stopped(childName: String) extends Effect
  @SerialVersionUID(1L) final case class Watched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class Unwatched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class ReceiveTimeoutSet[T](d: Duration, msg: T) extends Effect
  @SerialVersionUID(1L) final case class Messaged[U](other: ActorRef[U], msg: U) extends Effect
  @SerialVersionUID(1L) final case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect
  @SerialVersionUID(1L) case object EmptyEffect extends Effect
}

/**
 * An [[ActorContext]] for testing purposes that records the effects performed
 * on it and otherwise stubs them out like a [[StubbedActorContext]].
 */
class EffectfulActorContext[T](_name: String, _initialBehavior: Behavior[T], _mailboxCapacity: Int, _system: ActorSystem[Nothing])
  extends StubbedActorContext[T](_name, _mailboxCapacity, _system) {
  import Effect._
  import akka.{ actor ⇒ a }

  private val effectQueue = new ConcurrentLinkedQueue[Effect]
  def getEffect(): Effect = effectQueue.poll() match {
    case null ⇒ throw new NoSuchElementException(s"polling on an empty effect queue: $name")
    case x    ⇒ x
  }
  def getAllEffects(): immutable.Seq[Effect] = {
    @tailrec def rec(acc: List[Effect]): List[Effect] = effectQueue.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }
    rec(Nil)
  }
  def hasEffects: Boolean = effectQueue.peek() != null

  private var current = Behavior.validateAsInitial(Behavior.undefer(_initialBehavior, this))

  def currentBehavior: Behavior[T] = current
  def isAlive: Boolean = Behavior.isAlive(current)

  private def handleException: Catcher[Unit] = {
    case NonFatal(e) ⇒
      try Behavior.canonicalize(Behavior.interpretSignal(current, this, PostStop), current, this) // TODO why canonicalize here?
      catch { case NonFatal(ex) ⇒ /* ignore, real is logging */ }
      throw e
  }

  def run(msg: T): Unit = {
    try {
      current = Behavior.canonicalize(Behavior.interpretMessage(current, this, msg), current, this)
    } catch handleException
  }

  def signal(signal: Signal): Unit = {
    try {
      current = Behavior.canonicalize(Behavior.interpretSignal(current, this, signal), current, this)
    } catch handleException
  }

  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] = {
    val ref = super.spawnAnonymous(behavior, props)
    effectQueue.offer(SpawnedAnonymous(props))
    ref
  }

  override def spawnAdapter[U](f: U ⇒ T): ActorRef[U] = {
    spawnAdapter(f, "")
  }

  override def spawnAdapter[U](f: U ⇒ T, name: String): ActorRef[U] = {
    val ref = super.spawnAdapter(f, name)
    effectQueue.offer(SpawnedAdapter)
    ref
  }
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] = {
    effectQueue.offer(Spawned(name, props))
    super.spawn(behavior, name, props)
  }
  override def stop[U](child: ActorRef[U]): Boolean = {
    effectQueue.offer(Stopped(child.path.name))
    super.stop(child)
  }
  override def watch[U](other: ActorRef[U]): Unit = {
    effectQueue.offer(Watched(other))
    super.watch(other)
  }
  override def unwatch[U](other: ActorRef[U]): Unit = {
    effectQueue.offer(Unwatched(other))
    super.unwatch(other)
  }
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = {
    effectQueue.offer(ReceiveTimeoutSet(d, msg))
    super.setReceiveTimeout(d, msg)
  }
  override def cancelReceiveTimeout(): Unit = {
    effectQueue.offer(ReceiveTimeoutSet(Duration.Undefined, null))
    super.cancelReceiveTimeout()
  }
  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    effectQueue.offer(Scheduled(delay, target, msg))
    super.schedule(delay, target, msg)
  }
}
