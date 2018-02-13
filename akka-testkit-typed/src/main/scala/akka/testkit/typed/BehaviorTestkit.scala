/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.language.existentials
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

import akka.actor.typed.internal.ControlledExecutor
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Props
import akka.actor.typed.Signal
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi

/**
 * All tracked effects must extend implement this type. It is deliberately
 * not sealed in order to allow extensions.
 */
abstract class Effect

// TODO offer a better Java API for default params that are rarely used e.g. props
@ApiMayChange
object Effect {

  abstract class SpawnedEffect extends Effect

  @SerialVersionUID(1L) final case class Spawned(behavior: Behavior[_], childName: String, props: Props = Props.empty) extends SpawnedEffect
  @SerialVersionUID(1L) final case class SpawnedAnonymous(behavior: Behavior[_], props: Props = Props.empty) extends SpawnedEffect
  @SerialVersionUID(1L) final case object SpawnedAdapter extends SpawnedEffect
  @SerialVersionUID(1L) final case class Stopped(childName: String) extends Effect
  @SerialVersionUID(1L) final case class Watched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class Unwatched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class ReceiveTimeoutSet[T](d: Duration, msg: T) extends Effect
  @SerialVersionUID(1L) final case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect
  @SerialVersionUID(1L) case object NoEffects extends Effect

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class EffectfulActorContext[T](name: String) extends StubbedActorContext[T](name) {

  import Effect._
  import akka.{ actor ⇒ a }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val effectQueue = new ConcurrentLinkedQueue[Effect]

  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] = {
    val ref = super.spawnAnonymous(behavior, props)
    effectQueue.offer(SpawnedAnonymous(behavior, props))
    ref
  }

  override def spawnMessageAdapter[U](f: U ⇒ T): ActorRef[U] = {
    spawnMessageAdapter(f, "")
  }

  override def spawnMessageAdapter[U](f: U ⇒ T, name: String): ActorRef[U] = {
    val ref = super.spawnMessageAdapter(f, name)
    effectQueue.offer(SpawnedAdapter)
    ref
  }
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] = {
    effectQueue.offer(Spawned(behavior, name, props))
    super.spawn(behavior, name, props)
  }
  override def stop[U](child: ActorRef[U]): Unit = {
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

@ApiMayChange
object BehaviorTestkit {
  def apply[T](initialBehavior: Behavior[T], name: String): BehaviorTestkit[T] =
    new BehaviorTestkit[T](name, initialBehavior)
  def apply[T](initialBehavior: Behavior[T]): BehaviorTestkit[T] =
    apply(initialBehavior, "testkit")

  /**
   * JAVA API
   */
  def create[T](initialBehavior: Behavior[T], name: String): BehaviorTestkit[T] =
    new BehaviorTestkit[T](name, initialBehavior)
  /**
   * JAVA API
   */
  def create[T](initialBehavior: Behavior[T]): BehaviorTestkit[T] =
    apply(initialBehavior, "testkit")
}

/**
 * Used for testing [[Behavior]]s. Stores all effects e.g. Spawning of children,
 * watching and offers access to what effects have taken place.
 */
@ApiMayChange
class BehaviorTestkit[T] private (_name: String, _initialBehavior: Behavior[T]) {

  import Effect._

  // really this should be private, make so when we port out tests that need it
  private[akka] val ctx = new EffectfulActorContext[T](_name)

  /**
   * Requests the oldest [[Effect]] or [[NoEffects]] if no effects
   * have taken place. The effect is consumed, subsequent calls won't
   * will not include this effect.
   */
  def retrieveEffect(): Effect = ctx.effectQueue.poll() match {
    case null ⇒ NoEffects
    case x    ⇒ x
  }

  def childInbox[U](name: String): TestInbox[U] = {
    val inbox = ctx.childInbox[U](name)
    assert(inbox.isDefined, s"Child not created: $name. Children created: [${ctx.childrenNames.mkString(",")}]")
    inbox.get
  }

  def selfInbox(): TestInbox[T] = ctx.selfInbox

  /**
   * Requests all the effects. The effects are consumed, subsequent calls will only
   * see new effects.
   */
  def retrieveAllEffects(): immutable.Seq[Effect] = {
    @tailrec def rec(acc: List[Effect]): List[Effect] = ctx.effectQueue.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }

    rec(Nil)
  }

  /**
   * Asserts that the oldest effect is the expectedEffect. Removing it from
   * further assertions.
   */
  def expectEffect(expectedEffect: Effect): Unit = {
    ctx.effectQueue.poll() match {
      case null   ⇒ assert(assertion = false, s"expected: $expectedEffect but no effects were recorded")
      case effect ⇒ assert(expectedEffect == effect, s"expected: $expectedEffect but found $effect")
    }
  }

  private var current = Behavior.validateAsInitial(Behavior.undefer(_initialBehavior, ctx))

  def currentBehavior: Behavior[T] = current
  def isAlive: Boolean = Behavior.isAlive(current)

  private def handleException: Catcher[Unit] = {
    case NonFatal(e) ⇒
      try Behavior.canonicalize(Behavior.interpretSignal(current, ctx, PostStop), current, ctx) // TODO why canonicalize here?
      catch {
        case NonFatal(_) ⇒ /* ignore, real is logging */
      }
      throw e
  }

  /**
   * Send the msg to the behavior and record any [[Effect]]s
   */
  def run(msg: T): Unit = {
    try {
      current = Behavior.canonicalize(Behavior.interpretMessage(current, ctx, msg), current, ctx)
      ctx.executionContext match {
        case controlled: ControlledExecutor ⇒ controlled.runAll()
        case _                              ⇒
      }
    } catch handleException
  }

  /**
   * Send the signal to the beheavior and record any [[Effect]]s
   */
  def signal(signal: Signal): Unit = {
    try {
      current = Behavior.canonicalize(Behavior.interpretSignal(current, ctx, signal), current, ctx)
    } catch handleException
  }

}
