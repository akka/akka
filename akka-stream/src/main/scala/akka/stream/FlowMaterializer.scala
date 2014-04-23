/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorRefFactory
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.impl.Ast
import org.reactivestreams.api.Producer
import scala.concurrent.duration._
import org.reactivestreams.api.Consumer

object FlowMaterializer {

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
  def apply(settings: MaterializerSettings, namePrefix: Option[String] = None)(implicit context: ActorRefFactory): FlowMaterializer =
    new ActorBasedFlowMaterializer(settings, context, namePrefix.getOrElse("flow"))

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   */
  def create(settings: MaterializerSettings, context: ActorRefFactory): FlowMaterializer =
    apply(settings)(context)
}

/**
 * A FlowMaterializer takes the list of transformations comprising a
 * [[akka.stream.scaladsl.Flow]] and materializes them in the form of
 * [[org.reactivestreams.api.Processor]] instances. How transformation
 * steps are split up into asynchronous regions is implementation
 * dependent.
 */
abstract class FlowMaterializer {

  /**
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps.
   */
  def withNamePrefix(name: String): FlowMaterializer

  /**
   * INTERNAL API
   * ops are stored in reverse order
   */
  private[akka] def toProducer[I, O](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]): Producer[O]
  /**
   * INTERNAL API
   */
  private[akka] def consume[I](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]): Unit

  /**
   * INTERNAL API
   */
  private[akka] def ductProduceTo[In, Out](consumer: Consumer[Out], ops: List[Ast.AstNode]): Consumer[In]

  /**
   * INTERNAL API
   */
  private[akka] def ductConsume[In](ops: List[Ast.AstNode]): Consumer[In]

  /**
   * INTERNAL API
   */
  private[akka] def ductBuild[In, Out](ops: List[Ast.AstNode]): (Consumer[In], Producer[Out])

}

object MaterializerSettings {
  private val defaultSettings = new MaterializerSettings
  /**
   * Java API: Default settings.
   * Refine the settings using [[MaterializerSettings#withBuffer]],
   * [[MaterializerSettings#withFanOut]], [[MaterializerSettings#withSubscriptionTimeout]]
   */
  def create(): MaterializerSettings = defaultSettings
}

/**
 * The buffers employed by the generated Processors can be configured by
 * creating an appropriate instance of this class.
 *
 * This will likely be replaced in the future by auto-tuning these values at runtime.
 */
case class MaterializerSettings(
  initialFanOutBufferSize: Int = 4,
  maxFanOutBufferSize: Int = 16,
  initialInputBufferSize: Int = 4,
  maximumInputBufferSize: Int = 16,
  upstreamSubscriptionTimeout: FiniteDuration = 3.seconds,
  downstreamSubscriptionTimeout: FiniteDuration = 3.seconds) {

  private def isPowerOfTwo(n: Integer): Boolean = (n & (n - 1)) == 0
  require(initialFanOutBufferSize > 0, "initialFanOutBufferSize must be > 0")
  require(maxFanOutBufferSize > 0, "maxFanOutBufferSize must be > 0")
  require(initialFanOutBufferSize <= maxFanOutBufferSize,
    s"initialFanOutBufferSize($initialFanOutBufferSize) must be <= maxFanOutBufferSize($maxFanOutBufferSize)")

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")
  require(isPowerOfTwo(initialInputBufferSize), "initialInputBufferSize must be a power of two")
  require(maximumInputBufferSize > 0, "maximumInputBufferSize must be > 0")
  require(isPowerOfTwo(maximumInputBufferSize), "initialInputBufferSize must be a power of two")
  require(initialInputBufferSize <= maximumInputBufferSize,
    s"initialInputBufferSize($initialInputBufferSize) must be <= maximumInputBufferSize($maximumInputBufferSize)")

  def withBuffer(initialInputBufferSize: Int, maximumInputBufferSize: Int): MaterializerSettings =
    copy(initialInputBufferSize = initialInputBufferSize, maximumInputBufferSize = maximumInputBufferSize)

  def withFanOut(initialFanOutBufferSize: Int, maxFanOutBufferSize: Int): MaterializerSettings =
    copy(initialFanOutBufferSize = initialFanOutBufferSize, maxFanOutBufferSize = maxFanOutBufferSize)

  def withSubscriptionTimeout(timeout: FiniteDuration): MaterializerSettings =
    copy(upstreamSubscriptionTimeout = timeout, downstreamSubscriptionTimeout = timeout)

  def withSubscriptionTimeout(upstreamSubscriptionTimeout: FiniteDuration,
                              downstreamSubscriptionTimeout: FiniteDuration): MaterializerSettings =
    copy(upstreamSubscriptionTimeout = upstreamSubscriptionTimeout,
      downstreamSubscriptionTimeout = downstreamSubscriptionTimeout)

}

