/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.ActorContext
import akka.stream.ActorMaterializerSettings

object ActorMaterializer {
  import akka.actor.typed.scaladsl.adapter._

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.typed.ActorSystem]]
   * will be used to create one actor that in turn creates actors for the transformation steps.
   *
   * The materializer's [[akka.stream.ActorMaterializerSettings]] will be obtained from the
   * configuration of the `context`'s underlying [[akka.actor.typed.ActorSystem]].
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply[T](materializerSettings: Option[ActorMaterializerSettings] = None, namePrefix: Option[String] = None)(implicit actorSystem: ActorSystem[T]): ActorMaterializer =
    akka.stream.ActorMaterializer(materializerSettings, namePrefix)(actorSystem.toUntyped)

  /**
   * Creates an `ActorMaterializer` which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The lifecycle of the materialized streams
   * will be bound to the lifecycle of the provided [[akka.actor.typed.scaladsl.ActorContext]]
   *
   * The materializer's [[akka.stream.ActorMaterializerSettings]] will be obtained from the
   * configuration of the `context`'s underlying [[akka.actor.typed.ActorSystem]].
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def boundToActor[T](ctx: ActorContext[T], materializerSettings: Option[ActorMaterializerSettings] = None, namePrefix: Option[String] = None): ActorMaterializer =
    akka.stream.ActorMaterializer(materializerSettings, namePrefix)(ctx.toUntyped)

}
