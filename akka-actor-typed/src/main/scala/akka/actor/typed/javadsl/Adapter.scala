/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import akka.actor
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.EmptyProps
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorSystem
import akka.actor.typed.internal.adapter.ActorContextAdapter
import akka.japi.Creator

/**
 * Java API: Adapters between typed and untyped actors and actor systems.
 * The underlying `ActorSystem` is the untyped [[akka.actor.ActorSystem]]
 * which runs Akka Typed [[akka.actor.typed.Behavior]] on an emulation layer. In this
 * system typed and untyped actors can coexist.
 *
 * These methods make it possible to create typed child actor from untyped
 * parent actor, and the opposite untyped child from typed parent.
 * `watch` is also supported in both directions.
 *
 * There are also converters (`toTyped`, `toClassic`) between untyped
 * [[akka.actor.ActorRef]] and typed [[akka.actor.typed.ActorRef]], and between untyped
 * [[akka.actor.ActorSystem]] and typed [[akka.actor.typed.ActorSystem]].
 */
object Adapter {

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorSystem.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawnAnonymous[T](sys: akka.actor.ActorSystem, behavior: Behavior[T]): ActorRef[T] =
    spawnAnonymous(sys, behavior, EmptyProps)

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorSystem.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawnAnonymous[T](sys: akka.actor.ActorSystem, behavior: Behavior[T], props: Props): ActorRef[T] =
    sys.spawnAnonymous(behavior, props)

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorSystem.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawn[T](sys: akka.actor.ActorSystem, behavior: Behavior[T], name: String): ActorRef[T] =
    spawn(sys, behavior, name, EmptyProps)

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorSystem.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawn[T](sys: akka.actor.ActorSystem, behavior: Behavior[T], name: String, props: Props): ActorRef[T] =
    sys.spawn(behavior, name, props)

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorContext.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawnAnonymous[T](ctx: akka.actor.ActorContext, behavior: Behavior[T]): ActorRef[T] =
    spawnAnonymous(ctx, behavior, EmptyProps)

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorContext.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawnAnonymous[T](ctx: akka.actor.ActorContext, behavior: Behavior[T], props: Props): ActorRef[T] =
    ctx.spawnAnonymous(behavior, props)

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorContext.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawn[T](ctx: akka.actor.ActorContext, behavior: Behavior[T], name: String): ActorRef[T] =
    spawn(ctx, behavior, name, EmptyProps)

  /**
   *  Spawn the given behavior as a child of the user actor in an untyped ActorContext.
   *  Typed actors default supervision strategy is to stop. Can be overridden with
   *  `Behaviors.supervise`.
   */
  def spawn[T](ctx: akka.actor.ActorContext, behavior: Behavior[T], name: String, props: Props): ActorRef[T] =
    ctx.spawn(behavior, name, props)

  def toTyped(sys: akka.actor.ActorSystem): ActorSystem[Void] =
    sys.toTyped.asInstanceOf[ActorSystem[Void]]

  @deprecated("Use 'toClassic' instead", "2.5.26")
  def toUntyped(sys: ActorSystem[_]): akka.actor.ActorSystem = toClassic(sys)

  def toClassic(sys: ActorSystem[_]): akka.actor.ActorSystem = sys.toClassic

  @deprecated("Use 'toClassic' instead", "2.5.26")
  def toUntyped(ctx: ActorContext[_]): actor.ActorContext = toClassic(ctx)

  def toClassic(ctx: ActorContext[_]): akka.actor.ActorContext = ActorContextAdapter.toClassic(ctx)

  def watch[U](ctx: akka.actor.ActorContext, other: ActorRef[U]): Unit =
    ctx.watch(other)

  def unwatch[U](ctx: akka.actor.ActorContext, other: ActorRef[U]): Unit =
    ctx.unwatch(other)

  def stop(ctx: akka.actor.ActorContext, child: ActorRef[_]): Unit =
    ctx.stop(child)

  def watch[U](ctx: ActorContext[_], other: akka.actor.ActorRef): Unit =
    ctx.watch(other)

  def unwatch[U](ctx: ActorContext[_], other: akka.actor.ActorRef): Unit =
    ctx.unwatch(other)

  def stop(ctx: ActorContext[_], child: akka.actor.ActorRef): Unit =
    ctx.stop(child)

  def actorOf(ctx: ActorContext[_], props: akka.actor.Props): akka.actor.ActorRef =
    ActorContextAdapter.toClassic(ctx).actorOf(props)

  def actorOf(ctx: ActorContext[_], props: akka.actor.Props, name: String): akka.actor.ActorRef =
    ActorContextAdapter.toClassic(ctx).actorOf(props, name)

  @deprecated("Use 'toClassic' instead", "2.5.26")
  def toUntyped(ref: ActorRef[_]): akka.actor.ActorRef = toClassic(ref)

  def toClassic(ref: ActorRef[_]): akka.actor.ActorRef = ref.toClassic

  def toTyped[T](ref: akka.actor.ActorRef): ActorRef[T] =
    ref

  /**
   * Wrap [[akka.actor.typed.Behavior]] in an untyped [[akka.actor.Props]], i.e. when
   * spawning a typed child actor from an untyped parent actor.
   * This is normally not needed because you can use the extension methods
   * `spawn` and `spawnAnonymous` with an untyped `ActorContext`, but it's needed
   * when using typed actors with an existing library/tool that provides an API that
   * takes an untyped [[akka.actor.Props]] parameter. Cluster Sharding is an
   * example of that.
   */
  def props[T](behavior: Creator[Behavior[T]], deploy: Props): akka.actor.Props =
    akka.actor.typed.internal.adapter.PropsAdapter(() => behavior.create(), deploy)

  /**
   * Wrap [[akka.actor.typed.Behavior]] in an untyped [[akka.actor.Props]], i.e. when
   * spawning a typed child actor from an untyped parent actor.
   * This is normally not needed because you can use the extension methods
   * `spawn` and `spawnAnonymous` with an untyped `ActorContext`, but it's needed
   * when using typed actors with an existing library/tool that provides an API that
   * takes an untyped [[akka.actor.Props]] parameter. Cluster Sharding is an
   * example of that.
   */
  def props[T](behavior: Creator[Behavior[T]]): akka.actor.Props =
    props(behavior, EmptyProps)
}
