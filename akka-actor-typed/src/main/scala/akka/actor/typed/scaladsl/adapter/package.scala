/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.actor.ExtendedActorSystem
import akka.actor.typed.internal.adapter.{ PropsAdapter => _, _ }
import akka.annotation.InternalApi

/**
 * Scala API: Adapters between typed and untyped actors and actor systems.
 * The underlying `ActorSystem` is the untyped [[akka.actor.ActorSystem]]
 * which runs Akka Typed [[akka.actor.typed.Behavior]] on an emulation layer. In this
 * system typed and untyped actors can coexist.
 *
 * Use these adapters with `import akka.actor.typed.scaladsl.adapter._`.
 *
 * Implicit extension methods are added to untyped and typed `ActorSystem`,
 * `ActorContext`. Such methods make it possible to create typed child actor
 * from untyped parent actor, and the opposite untyped child from typed parent.
 * `watch` is also supported in both directions.
 *
 * There is an implicit conversion from untyped [[akka.actor.ActorRef]] to
 * typed [[akka.actor.typed.ActorRef]].
 *
 * There are also converters (`toTyped`, `toUntyped`) from typed
 * [[akka.actor.typed.ActorRef]] to untyped [[akka.actor.ActorRef]], and between untyped
 * [[akka.actor.ActorSystem]] and typed [[akka.actor.typed.ActorSystem]].
 */
package object adapter {

  import language.implicitConversions

  /**
   * Extension methods added to [[akka.actor.ActorSystem]].
   */
  implicit class UntypedActorSystemOps(val sys: akka.actor.ActorSystem) extends AnyVal {

    /**
     *  Spawn the given behavior as a child of the user actor in an untyped ActorSystem.
     *
     *  Typed actors default supervision strategy is to stop. Can be overridden with
     *  `Behaviors.supervise`.
     */
    def spawnAnonymous[T](behavior: Behavior[T], props: Props = Props.empty): ActorRef[T] = {
      ActorRefFactoryAdapter.spawnAnonymous(
        sys,
        Behaviors.supervise(behavior).onFailure(SupervisorStrategy.stop),
        props,
        rethrowTypedFailure = false)
    }

    /**
     *  Spawn the given behavior as a child of the user actor in an untyped ActorSystem.
     *
     *  Typed actors default supervision strategy is to stop. Can be overridden with
     *  `Behaviors.supervise`.
     */
    def spawn[T](behavior: Behavior[T], name: String, props: Props = Props.empty): ActorRef[T] = {
      ActorRefFactoryAdapter.spawn(
        sys,
        Behaviors.supervise(behavior).onFailure(SupervisorStrategy.stop),
        name,
        props,
        rethrowTypedFailure = false)
    }

    def toTyped: ActorSystem[Nothing] = AdapterExtension(sys).adapter
  }

  /**
   * Extension methods added to [[akka.actor.typed.ActorSystem]].
   */
  implicit class TypedActorSystemOps(val sys: ActorSystem[_]) extends AnyVal {
    def toUntyped: akka.actor.ActorSystem = ActorSystemAdapter.toUntyped(sys)

    /**
     * INTERNAL API
     */
    @InternalApi private[akka] def internalSystemActorOf[U](
        behavior: Behavior[U],
        name: String,
        props: Props): ActorRef[U] = {
      toUntyped.asInstanceOf[ExtendedActorSystem].systemActorOf(PropsAdapter(behavior, props), name)
    }
  }

  /**
   * Extension methods added to [[akka.actor.ActorContext]].
   */
  implicit class UntypedActorContextOps(val ctx: akka.actor.ActorContext) extends AnyVal {

    /**
     *  Spawn the given behavior as a child of the user actor in an untyped ActorContext.
     *
     *  Typed actors default supervision strategy is to stop. Can be overridden with
     *  `Behaviors.supervise`.
     */
    def spawnAnonymous[T](behavior: Behavior[T], props: Props = Props.empty): ActorRef[T] =
      ActorRefFactoryAdapter.spawnAnonymous(
        ctx,
        Behaviors.supervise(behavior).onFailure(SupervisorStrategy.stop),
        props,
        rethrowTypedFailure = false)

    /**
     *  Spawn the given behavior as a child of the user actor in an untyped ActorContext.
     *
     *  Typed actors default supervision strategy is to stop. Can be overridden with
     *  `Behaviors.supervise`.
     */
    def spawn[T](behavior: Behavior[T], name: String, props: Props = Props.empty): ActorRef[T] =
      ActorRefFactoryAdapter.spawn(
        ctx,
        Behaviors.supervise(behavior).onFailure(SupervisorStrategy.stop),
        name,
        props,
        rethrowTypedFailure = false)

    def watch[U](other: ActorRef[U]): Unit = ctx.watch(ActorRefAdapter.toUntyped(other))
    def unwatch[U](other: ActorRef[U]): Unit = ctx.unwatch(ActorRefAdapter.toUntyped(other))

    def stop(child: ActorRef[_]): Unit =
      ctx.stop(ActorRefAdapter.toUntyped(child))
  }

  /**
   * Extension methods added to [[akka.actor.typed.scaladsl.ActorContext]].
   */
  implicit class TypedActorContextOps(val ctx: scaladsl.ActorContext[_]) extends AnyVal {
    def actorOf(props: akka.actor.Props): akka.actor.ActorRef =
      ActorContextAdapter.toUntyped(ctx).actorOf(props)

    def actorOf(props: akka.actor.Props, name: String): akka.actor.ActorRef =
      ActorContextAdapter.toUntyped(ctx).actorOf(props, name)

    def toUntyped: akka.actor.ActorContext = ActorContextAdapter.toUntyped(ctx)

    // watch, unwatch and stop not needed here because of the implicit ActorRef conversion
  }

  /**
   * Extension methods added to [[akka.actor.typed.ActorRef]].
   */
  implicit class TypedActorRefOps(val ref: ActorRef[_]) extends AnyVal {
    def toUntyped: akka.actor.ActorRef = ActorRefAdapter.toUntyped(ref)
  }

  /**
   * Extension methods added to [[akka.actor.ActorRef]].
   */
  implicit class UntypedActorRefOps(val ref: akka.actor.ActorRef) extends AnyVal {

    /**
     * Adapt the untyped `ActorRef` to typed `ActorRef[T]`. There is also an
     * automatic implicit conversion for this, but this more explicit variant might
     * sometimes be preferred.
     */
    def toTyped[T]: ActorRef[T] = ActorRefAdapter(ref)
  }

  /**
   * Implicit conversion from untyped [[akka.actor.ActorRef]] to typed [[akka.actor.typed.ActorRef]].
   */
  implicit def actorRefAdapter[T](ref: akka.actor.ActorRef): ActorRef[T] = ActorRefAdapter(ref)

}
