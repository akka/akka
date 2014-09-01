/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorRefFactory
import akka.stream.impl2.ActorBasedFlowMaterializer
import akka.stream.impl2.Ast
import org.reactivestreams.{ Publisher, Subscriber }
import scala.concurrent.duration._
import akka.actor.Deploy
import akka.actor.ExtendedActorSystem
import akka.actor.ActorContext
import akka.stream.impl2.StreamSupervisor
import akka.stream.impl2.FlowNameCounter
import akka.stream.MaterializerSettings
import org.reactivestreams.Processor

object FlowMaterializer {

  /**
   * Scala API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(settings: MaterializerSettings, namePrefix: Option[String] = None)(implicit context: ActorRefFactory): FlowMaterializer = {
    val system = context match {
      case s: ExtendedActorSystem ⇒ s
      case c: ActorContext        ⇒ c.system
      case null                   ⇒ throw new IllegalArgumentException("ActorRefFactory context must be defined")
      case _ ⇒ throw new IllegalArgumentException(s"ActorRefFactory context must be a ActorSystem or ActorContext, " +
        "got [${_contex.getClass.getName}]")
    }

    new ActorBasedFlowMaterializer(
      settings,
      context.actorOf(StreamSupervisor.props(settings).withDispatcher(settings.dispatcher)),
      FlowNameCounter(system).counter,
      namePrefix.getOrElse("flow"))
  }

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create(settings: MaterializerSettings, context: ActorRefFactory): FlowMaterializer =
    apply(settings)(context)
}

/**
 * A FlowMaterializer takes the list of transformations comprising a
 * [[akka.stream.scaladsl.Flow]] and materializes them in the form of
 * [[org.reactivestreams.Processor]] instances. How transformation
 * steps are split up into asynchronous regions is implementation
 * dependent.
 */
abstract class FlowMaterializer(val settings: MaterializerSettings) {

  /**
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps.
   */
  def withNamePrefix(name: String): FlowMaterializer

  /**
   * INTERNAL API
   * ops are stored in reverse order
   */
  private[akka] def materialize[In, Out](source: Source[In], sink: Sink[Out], ops: List[Ast.AstNode]): MaterializedFlow

  /**
   * INTERNAL API
   */
  private[akka] def identityProcessor[I](flowName: String): Processor[I, I]

}

