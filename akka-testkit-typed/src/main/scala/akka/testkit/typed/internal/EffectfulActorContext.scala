/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.internal

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.Cancellable
import akka.actor.typed.{ ActorRef, Behavior, Props }
import akka.annotation.InternalApi
import akka.testkit.typed.Effect
import akka.testkit.typed.scaladsl.Effects._

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class EffectfulActorContext[T](name: String) extends StubbedActorContext[T](name) {

  private[akka] val effectQueue = new ConcurrentLinkedQueue[Effect]

  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] = {
    val ref = super.spawnAnonymous(behavior, props)
    effectQueue.offer(SpawnedAnonymous(behavior, props))
    ref
  }

  override def spawnMessageAdapter[U](f: U ⇒ T): ActorRef[U] = {
    val ref = super.spawnMessageAdapter(f)
    effectQueue.offer(SpawnedAdapter)
    ref
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
  override def watchWith[U](other: ActorRef[U], msg: T): Unit = {
    effectQueue.offer(Watched(other))
    super.watchWith(other, msg)
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
  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): Cancellable = {
    effectQueue.offer(Scheduled(delay, target, msg))
    super.schedule(delay, target, msg)
  }
}

