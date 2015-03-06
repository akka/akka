/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.Actor
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.ActorFlowMaterializer

/**
 * Mix this trait into your [[akka.actor.Actor]] if you need an implicit
 * [[akka.stream.FlowMaterializer]] in scope.
 *
 * Subclass may override [[#flowMaterializerSettings]] to define custom
 * [[akka.stream.ActorFlowMaterializerSettings]] for the `FlowMaterializer`.
 */
trait ImplicitFlowMaterializer { this: Actor ⇒

  /**
   * Subclass may override this to define custom
   * [[akka.stream.ActorFlowMaterializerSettings]] for the `FlowMaterializer`.
   */
  def flowMaterializerSettings: ActorFlowMaterializerSettings = ActorFlowMaterializerSettings(context.system)

  final implicit val flowMaterializer: ActorFlowMaterializer = ActorFlowMaterializer(Some(flowMaterializerSettings))
}
