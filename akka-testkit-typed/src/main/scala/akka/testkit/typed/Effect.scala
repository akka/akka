/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed

import akka.actor.typed.{ ActorRef, Behavior, Props }
import akka.annotation.{ ApiMayChange, DoNotInherit }

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * All tracked effects for the [[akka.testkit.typed.scaladsl.BehaviorTestKit]] and
 * [[akka.testkit.typed.javadsl.BehaviorTestKit]] must extend this type.
 *
 * Not for user extension
 */
@DoNotInherit
sealed abstract class Effect

/**
 * Factories/types for effects, each effect has a suitable equals and can be used to compare actual effects
 * to expected ones.
 */
@ApiMayChange
object Effect {

  // FIXME is this one really a useful subgroup?
  abstract class SpawnedEffect extends Effect

  final case class Spawned[T](behavior: Behavior[T], childName: String, props: Props = Props.empty) extends SpawnedEffect

  /**
   * Java API:
   */
  def spawned[T](behavior: Behavior[T], childName: String): SpawnedEffect = Spawned(behavior, childName)
  /**
   * Java API:
   */
  def spawned[T](behavior: Behavior[T], childName: String, props: Props): SpawnedEffect = Spawned(behavior, childName, props)

  final case class SpawnedAnonymous[T](behavior: Behavior[T], props: Props = Props.empty) extends SpawnedEffect

  /**
   * Java API:
   */
  def spawnedAnonymous(behavior: Behavior[_]): Effect = SpawnedAnonymous(behavior)
  /**
   * Java API:
   */
  def spawnedAnonymous(behavior: Behavior[_], props: Props): Effect = SpawnedAnonymous(behavior, props)

  final case object SpawnedAdapter extends SpawnedEffect

  /**
   * Java API:
   */
  def spawnedAdapter: Effect = SpawnedAdapter

  final case class Stopped(childName: String) extends Effect

  /**
   * Java API:
   */
  def stopped(childName: String): Effect = Stopped(childName)

  final case class Watched[T](other: ActorRef[T]) extends Effect

  /**
   * Java API:
   */
  def watched[T](other: ActorRef[T]): Effect = Watched(other)

  final case class Unwatched[T](other: ActorRef[T]) extends Effect
  /**
   * Java API:
   */
  def unwatched[T](other: ActorRef[T]): Effect = Unwatched(other)

  final case class ReceiveTimeoutSet[T](d: Duration, msg: T) extends Effect

  /**
   * Java API:
   */
  def receiveTimeoutSet[T](d: Duration, msg: T): Effect = ReceiveTimeoutSet(d, msg)

  final case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect

  /**
   * Java API:
   */
  def scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U): Effect =
    Scheduled(delay, target, msg)

  case object NoEffects extends Effect

  /**
   * Java API:
   */
  def noEffects(): Effect = NoEffects

}

