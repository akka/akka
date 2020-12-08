/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SpawnProtocol
import akka.actor.typed.TypedActorContext
import akka.actor.typed.internal.BehaviorImpl.DeferredBehavior
import akka.annotation.InternalApi
import akka.util.unused

/**
 * Initialization when creating an [[akka.actor.typed.ActorSystem]] can be performed in an implementation of
 * this [[akka.actor.typed.Behavior]]. Use it as the `guardianBehavior` when creating the `ActorSystem`.
 * Override one of the `init` methods and place the initialization code there.
 *
 * It also implements the [[akka.actor.typed.SpawnProtocol]].
 *
 * It can be used for other child actors than the guardian.
 */
abstract class Init extends DeferredBehavior[SpawnProtocol.Command] {

  /**
   * Override this method to perform initialization when the `ActorSystem` is started.
   * For example, starting Akka Management and Cluster Bootstrap, initializing Cluster Sharding,
   * starting projections or starting a gRPC server.
   */
  @throws(classOf[Exception])
  protected def init(@unused system: ActorSystem[_]): Unit = ()

  /**
   * Override this method to create initial child actors.
   */
  @throws(classOf[Exception])
  protected def init(@unused context: ActorContext[SpawnProtocol.Command]): Unit = ()

  /**
   * INTERNAL API
   */
  @InternalApi
  override final def apply(ctx: TypedActorContext[SpawnProtocol.Command]): Behavior[SpawnProtocol.Command] = {
    val context = ctx.asJava
    init(context.getSystem)
    init(context)
    SpawnProtocol()
  }

}
