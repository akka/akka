/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.javadsl

import java.time.Duration

import akka.actor.typed.{ ActorRef, Behavior, Props }
import akka.testkit.typed.Effect
import akka.util.JavaDurationConverters._

/**
 * Factories for behavior effects for [[BehaviorTestKit]], each effect has a suitable equals and can be used to compare
 * actual effects to expected ones.
 */
object Effects {
  import akka.testkit.typed.scaladsl.Effects._

  /**
   * The behavior spawned a named child with the given behavior with no specific props
   */
  def spawned[T](behavior: Behavior[T], childName: String): Effect = Spawned(behavior, childName)
  /**
   * The behavior spawned a named child with the given behavior and specific props
   */
  def spawned[T](behavior: Behavior[T], childName: String, props: Props): Effect = Spawned(behavior, childName, props)
  /**
   * The behavior spawned an anonymous child with the given behavior with no specific props
   */
  def spawnedAnonymous(behavior: Behavior[_]): Effect = SpawnedAnonymous(behavior)
  /**
   * The behavior spawned an anonymous child with the given behavior with specific props
   */
  def spawnedAnonymous(behavior: Behavior[_], props: Props): Effect = SpawnedAnonymous(behavior, props)
  /**
   * The behavior spawned an anonymous adapter, through `ctx.spawnMessageAdapter`
   */
  def spawnedAdapter: Effect = SpawnedAdapter
  /**
   * The behavior spawned a named adapter, through `ctx.spawnMessageAdapter`
   */
  def spawnedNamedAdapter(name: String): Effect = SpawnedNamedAdapter(name)
  /**
   * The behavior stopped `childName`
   */
  def stopped(childName: String): Effect = Stopped(childName)
  /**
   * The behavior started watching `other`, through `ctx.watch(other)`
   */
  def watched[T](other: ActorRef[T]): Effect = Watched(other)

  /**
   * The behavior started watching `other`, through `ctx.unwatch(other)`
   */
  def unwatched[T](other: ActorRef[T]): Effect = Unwatched(other)

  /**
   * The behavior set a new receive timeout, with `msg` as timeout notification
   */
  def receiveTimeoutSet[T](d: Duration, msg: T): Effect = ReceiveTimeoutSet(d.asScala, msg)

  /**
   * The behavior used `ctx.schedule` to schedule `msg` to be sent to `target` after `delay`
   * FIXME what about events scheduled through the scheduler?
   */
  def scheduled[U](delay: Duration, target: ActorRef[U], msg: U): Effect =
    Scheduled(delay.asScala, target, msg)

  /**
   * Used to represent an empty list of effects - in other words, the behavior didn't do anything observable
   */
  def noEffects(): Effect = NoEffects

}
