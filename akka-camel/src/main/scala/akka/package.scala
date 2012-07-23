/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import language.implicitConversions

import org.apache.camel.model.ProcessorDefinition

package object camel {
  /**
   * To allow using Actors with the Camel Route DSL:
   *
   * {{{
   * from("file://data/input/CamelConsumer").to(actor)
   * }}}
   */
  implicit def toActorRouteDefinition[T <: ProcessorDefinition[T]](definition: ProcessorDefinition[T]) = new ActorRouteDefinition(definition)
}