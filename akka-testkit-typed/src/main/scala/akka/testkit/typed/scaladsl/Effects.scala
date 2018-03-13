/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.scaladsl

import akka.actor.typed.{ ActorRef, Behavior, Props }
import akka.testkit.typed.Effect

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Types for behavior effects for [[BehaviorTestKit]], each effect has a suitable equals and can be used to compare
 * actual effects to expected ones.
 */
object Effects {

  /**
   * The behavior spawned a named child with the given behavior (and optionally specific props)
   */
  final case class Spawned[T](behavior: Behavior[T], childName: String, props: Props = Props.empty) extends Effect
  /**
   * The behavior spawned an anonymous child with the given behavior (and optionally specific props)
   */
  final case class SpawnedAnonymous[T](behavior: Behavior[T], props: Props = Props.empty) extends Effect
  /**
   * The behavior spawned an anonymous adapter, through `ctx.spawnMessageAdapter`
   */
  final case object SpawnedAdapter extends Effect
  /**
   * The behavior spawned a named adapter, through `ctx.spawnMessageAdapter`
   */
  final case class SpawnedNamedAdapter(name: String) extends Effect
  /**
   * The behavior stopped `childName`
   */
  final case class Stopped(childName: String) extends Effect
  /**
   * The behavior started watching `other`, through `ctx.watch(other)`
   */
  final case class Watched[T](other: ActorRef[T]) extends Effect

  /**
   * The behavior started watching `other`, through `ctx.unwatch(other)`
   */
  final case class Unwatched[T](other: ActorRef[T]) extends Effect
  /**
   * The behavior set a new receive timeout, with `msg` as timeout notification
   */
  final case class ReceiveTimeoutSet[T](d: Duration, msg: T) extends Effect

  /**
   * The behavior used `ctx.schedule` to schedule `msg` to be sent to `target` after `delay`
   * FIXME what about events scheduled through the scheduler?
   */
  final case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect

  /**
   * Used to represent an empty list of effects - in other words, the behavior didn't do anything observable
   */
  case object NoEffects extends Effect
}
