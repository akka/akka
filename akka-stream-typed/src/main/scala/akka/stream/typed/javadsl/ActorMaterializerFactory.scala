/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.javadsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.javadsl.{ ActorContext, Adapter }
import akka.stream.ActorMaterializerSettings

object ActorMaterializerFactory {
  import akka.actor.typed.scaladsl.adapter._

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.typed.ActorSystem]]
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * Defaults the actor name prefix used to name actors running the processing steps to `"flow"`.
   * The actor names are built up of `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create[T](actorSystem: ActorSystem[T]): akka.stream.ActorMaterializer =
    akka.stream.ActorMaterializer.create(actorSystem.toUntyped)

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.typed.ActorSystem]]
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create[T](settings: ActorMaterializerSettings, actorSystem: ActorSystem[T]): akka.stream.ActorMaterializer =
    akka.stream.ActorMaterializer.create(settings, actorSystem.toUntyped)

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.typed.ActorSystem]]
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create[T](
      settings: ActorMaterializerSettings,
      namePrefix: String,
      actorSystem: ActorSystem[T]): akka.stream.ActorMaterializer =
    akka.stream.ActorMaterializer.create(settings, actorSystem.toUntyped, namePrefix)

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The lifecycle of the materialized streams
   * will be bound to the lifecycle of the provided [[akka.actor.typed.javadsl.ActorContext]]
   *
   * Defaults the actor name prefix used to name actors running the processing steps to `"flow"`.
   * The actor names are built up of `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create[T](ctx: ActorContext[T]): akka.stream.ActorMaterializer =
    akka.stream.ActorMaterializer.create(Adapter.toUntyped(ctx))

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The lifecycle of the materialized streams
   * will be bound to the lifecycle of the provided [[akka.actor.typed.javadsl.ActorContext]]
   */
  def create[T](settings: ActorMaterializerSettings, ctx: ActorContext[T]): akka.stream.ActorMaterializer =
    akka.stream.ActorMaterializer.create(settings, Adapter.toUntyped(ctx))

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The lifecycle of the materialized streams
   * will be bound to the lifecycle of the provided [[akka.actor.typed.javadsl.ActorContext]]
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create[T](
      settings: ActorMaterializerSettings,
      namePrefix: String,
      ctx: ActorContext[T]): akka.stream.ActorMaterializer =
    akka.stream.ActorMaterializer.create(settings, Adapter.toUntyped(ctx), namePrefix)
}
