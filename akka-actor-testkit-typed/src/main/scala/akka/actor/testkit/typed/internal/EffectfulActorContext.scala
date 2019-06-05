/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function

import akka.actor.{ ActorPath, Cancellable }
import akka.actor.typed.{ ActorRef, Behavior, Props }
import akka.annotation.InternalApi
import akka.actor.testkit.typed.Effect
import akka.actor.testkit.typed.Effect._

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.compat.java8.FunctionConverters._

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class EffectfulActorContext[T](
    path: ActorPath,
    currentBehaviorProvider: () => Behavior[T])
    extends StubbedActorContext[T](path, currentBehaviorProvider) {

  private[akka] val effectQueue = new ConcurrentLinkedQueue[Effect]

  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] = {
    val ref = super.spawnAnonymous(behavior, props)
    effectQueue.offer(new SpawnedAnonymous(behavior, props, ref))
    ref
  }
  override def spawnMessageAdapter[U](f: U => T): ActorRef[U] = {
    val ref = super.spawnMessageAdapter(f)
    effectQueue.offer(new SpawnedAnonymousAdapter(ref))
    ref
  }
  override def spawnMessageAdapter[U](f: U => T, name: String): ActorRef[U] = {
    val ref = super.spawnMessageAdapter(f, name)
    effectQueue.offer(new SpawnedAdapter(name, ref))
    ref
  }
  override def messageAdapter[U: ClassTag](f: U => T): ActorRef[U] = {
    val ref = super.messageAdapter(f)
    effectQueue.offer(MessageAdapter(implicitly[ClassTag[U]].runtimeClass.asInstanceOf[Class[U]], f))
    ref
  }
  override def messageAdapter[U](messageClass: Class[U], f: function.Function[U, T]): ActorRef[U] = {
    val ref = super.messageAdapter(messageClass, f)
    effectQueue.offer(MessageAdapter[U, T](messageClass, f.asScala))
    ref
  }
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] = {
    val ref = super.spawn(behavior, name, props)
    effectQueue.offer(new Spawned(behavior, name, props, ref))
    ref
  }
  override def stop[U](child: ActorRef[U]): Unit = {
    effectQueue.offer(Stopped(child.path.name))
    super.stop(child)
  }
  override def watch[U](other: ActorRef[U]): Unit = {
    effectQueue.offer(Watched(other))
    super.watch(other)
  }
  override def watchWith[U](other: ActorRef[U], message: T): Unit = {
    effectQueue.offer(Watched(other))
    super.watchWith(other, message)
  }
  override def unwatch[U](other: ActorRef[U]): Unit = {
    effectQueue.offer(Unwatched(other))
    super.unwatch(other)
  }
  override def setReceiveTimeout(d: FiniteDuration, message: T): Unit = {
    effectQueue.offer(ReceiveTimeoutSet(d, message))
    super.setReceiveTimeout(d, message)
  }
  override def cancelReceiveTimeout(): Unit = {
    effectQueue.offer(ReceiveTimeoutCancelled)
    super.cancelReceiveTimeout()
  }
  override def scheduleOnce[U](delay: FiniteDuration, target: ActorRef[U], message: U): Cancellable = {
    effectQueue.offer(Scheduled(delay, target, message))
    super.scheduleOnce(delay, target, message)
  }
}
