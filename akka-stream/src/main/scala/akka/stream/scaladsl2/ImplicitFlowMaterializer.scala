/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.actor.Actor
import akka.stream.MaterializerSettings

/**
 * Mix this trait into your [[akka.actor.Actor]] if you need an implicit
 * [[akka.stream.FlowMaterializer]] in scope.
 *
 * Subclass may override [[#flowMaterializerSettings]] to define custom
 * [[akka.stream.MaterializerSettings]] for the `FlowMaterializer`.
 */
trait ImplicitFlowMaterializer { this: Actor ⇒

  /**
   * Subclass may override this to define custom
   * [[akka.stream.MaterializerSettings]] for the `FlowMaterializer`.
   */
  def flowMaterializerSettings: MaterializerSettings = MaterializerSettings(context.system)

  final implicit val flowMaterializer: FlowMaterializer = FlowMaterializer(Some(flowMaterializerSettings))
}
