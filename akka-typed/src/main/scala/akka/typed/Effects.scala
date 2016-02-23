/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.collection.immutable

/**
 * All tracked effects must extend implement this type. It is deliberately
 * not sealed in order to allow extensions.
 */
abstract class Effect

object Effect {
  @SerialVersionUID(1L) final case class Spawned(childName: String) extends Effect
  @SerialVersionUID(1L) final case class Stopped(childName: String) extends Effect
  @SerialVersionUID(1L) final case class Watched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class Unwatched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class ReceiveTimeoutSet(d: Duration) extends Effect
  @SerialVersionUID(1L) final case class Messaged[U](other: ActorRef[U], msg: U) extends Effect
  @SerialVersionUID(1L) final case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect
  @SerialVersionUID(1L) case object EmptyEffect extends Effect
}

/**
 * An [[ActorContext]] for testing purposes that records the effects performed
 * on it and otherwise stubs them out like a [[StubbedActorContext]].
 */
class EffectfulActorContext[T](_name: String, _props: Props[T], _system: ActorSystem[Nothing])
  extends StubbedActorContext[T](_name, _props)(_system) {
  import akka.{ actor ⇒ a }
  import Effect._

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

  private var current = props.creator()
  signal(PreStart)

  def currentBehavior: Behavior[T] = current

  def run(msg: T): Unit = current = Behavior.canonicalize(this, current.message(this, msg), current)
  def signal(signal: Signal): Unit = current = Behavior.canonicalize(this, current.management(this, signal), current)

  override def spawnAnonymous[U](props: Props[U]): ActorRef[U] = {
    val ref = super.spawnAnonymous(props)
    effectQueue.offer(Spawned(ref.untypedRef.path.name))
    ref
  }
  override def spawn[U](props: Props[U], name: String): ActorRef[U] = {
    effectQueue.offer(Spawned(name))
    super.spawn(props, name)
  }
  override def actorOf(props: a.Props): a.ActorRef = {
    val ref = super.actorOf(props)
    effectQueue.offer(Spawned(ref.path.name))
    ref
  }
  override def actorOf(props: a.Props, name: String): a.ActorRef = {
    effectQueue.offer(Spawned(name))
    super.actorOf(props, name)
  }
  override def stop(child: ActorRef[Nothing]): Boolean = {
    effectQueue.offer(Stopped(child.path.name))
    super.stop(child)
  }
  override def watch[U](other: ActorRef[U]): ActorRef[U] = {
    effectQueue.offer(Watched(other))
    super.watch(other)
  }
  override def unwatch[U](other: ActorRef[U]): ActorRef[U] = {
    effectQueue.offer(Unwatched(other))
    super.unwatch(other)
  }
  override def watch(other: akka.actor.ActorRef): other.type = {
    effectQueue.offer(Watched(ActorRef[Any](other)))
    super.watch(other)
  }
  override def unwatch(other: akka.actor.ActorRef): other.type = {
    effectQueue.offer(Unwatched(ActorRef[Any](other)))
    super.unwatch(other)
  }
  override def setReceiveTimeout(d: Duration): Unit = {
    effectQueue.offer(ReceiveTimeoutSet(d))
    super.setReceiveTimeout(d)
  }
  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    effectQueue.offer(Scheduled(delay, target, msg))
    super.schedule(delay, target, msg)
  }
}
