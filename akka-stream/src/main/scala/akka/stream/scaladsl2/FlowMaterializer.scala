/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.collection.immutable
import akka.actor.{ ActorContext, ActorRefFactory, ActorSystem, ExtendedActorSystem }
import akka.stream.MaterializerSettings
import akka.stream.impl2.{ ActorBasedFlowMaterializer, Ast, FlowNameCounter, StreamSupervisor }
import org.reactivestreams.Subscriber
import org.reactivestreams.Publisher

// TODO: Find this guy a better place to live than scaladsl, as it's exposed in javadsl too
object FlowMaterializer {

  /**
   * Scala API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   *
   * The materializer's [[akka.stream.MaterializerSettings]] will be obtained from the
   * configuration of the `context`'s underlying [[akka.actor.ActorSystem]].
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: Option[MaterializerSettings] = None, namePrefix: Option[String] = None)(implicit context: ActorRefFactory): FlowMaterializer = {
    val system = actorSystemOf(context)

    val settings = materializerSettings getOrElse MaterializerSettings(system)
    apply(settings, namePrefix.getOrElse("flow"))(context)
  }

  /**
   * Scala API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: MaterializerSettings, namePrefix: String)(implicit context: ActorRefFactory): FlowMaterializer = {
    val system = actorSystemOf(context)

    new ActorBasedFlowMaterializer(
      materializerSettings,
      context.actorOf(StreamSupervisor.props(materializerSettings).withDispatcher(materializerSettings.dispatcher)),
      FlowNameCounter(system).counter,
      namePrefix)
  }

  /**
   * Scala API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: MaterializerSettings)(implicit context: ActorRefFactory): FlowMaterializer =
    apply(Some(materializerSettings), None)

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * Defaults the actor name prefix used to name actors running the processing steps to `"flow"`.
   * The actor names are built up of `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(context: ActorRefFactory): FlowMaterializer =
    apply()(context)

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create(settings: MaterializerSettings, context: ActorRefFactory): FlowMaterializer =
    apply(Option(settings), None)(context)

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(settings: MaterializerSettings, context: ActorRefFactory, namePrefix: String): FlowMaterializer =
    apply(Option(settings), Option(namePrefix))(context)

  private def actorSystemOf(context: ActorRefFactory): ActorSystem = {
    val system = context match {
      case s: ExtendedActorSystem ⇒ s
      case c: ActorContext        ⇒ c.system
      case null                   ⇒ throw new IllegalArgumentException("ActorRefFactory context must be defined")
      case _ ⇒
        throw new IllegalArgumentException(s"ActorRefFactory context must be a ActorSystem or ActorContext, got [${context.getClass.getName}]")
    }
    system
  }

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
   * The `namePrefix` shall be used for deriving the names of processing
   * entities that are created during materialization. This is meant to aid
   * logging and error reporting both during materialization and while the
   * stream is running.
   */
  def withNamePrefix(name: String): FlowMaterializer

  /**
   * This method interprets the given Flow description and creates the running
   * stream. The result can be highly implementation specific, ranging from
   * local actor chains to remote-deployed processing networks.
   */
  def materialize[In, Out](source: Source[In], sink: Sink[Out], ops: List[Ast.AstNode]): MaterializedMap

  /**
   * Create publishers and subscribers for fan-in and fan-out operations.
   */
  def materializeJunction[In, Out](op: Ast.JunctionAstNode, inputCount: Int, outputCount: Int): (immutable.Seq[Subscriber[In]], immutable.Seq[Publisher[Out]])

}

/**
 * This exception or subtypes thereof should be used to signal materialization
 * failures.
 */
class MaterializationException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

