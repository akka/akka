/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed

import akka.actor.typed.ActorSystem
import akka.stream.ActorMaterializerSettings

object ActorMaterializer {
  import akka.actor.typed.scaladsl.adapter._

  /**
   * Scala API: Creates an ActorMaterializer which will execute every step of a transformation
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
  def apply[T](materializerSettings: Option[ActorMaterializerSettings] = None, namePrefix: Option[String] = None)(implicit actorSystem: ActorSystem[T]): akka.stream.ActorMaterializer =
    akka.stream.ActorMaterializer(materializerSettings, namePrefix)(actorSystem.toUntyped)

  /**
   * Java API: Creates an ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.typed.ActorSystem]]
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * Defaults the actor name prefix used to name actors running the processing steps to `"flow"`.
   * The actor names are built up of `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create[T](actorSystem: ActorSystem[T]): akka.stream.ActorMaterializer =
    apply()(actorSystem)

  /**
   * Java API: Creates an ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.typed.ActorSystem]]
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create[T](settings: ActorMaterializerSettings, actorSystem: ActorSystem[T]): akka.stream.ActorMaterializer =
    apply(Option(settings), None)(actorSystem)

  /**
   * Java API: Creates an ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.typed.ActorSystem]]
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create[T](settings: ActorMaterializerSettings, namePrefix: String, actorSystem: ActorSystem[T]): akka.stream.ActorMaterializer =
    apply(Option(settings), Option(namePrefix))(actorSystem)

}
