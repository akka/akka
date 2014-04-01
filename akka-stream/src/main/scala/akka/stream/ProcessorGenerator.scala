/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorRefFactory
import akka.stream.impl.ActorBasedProcessorGenerator
import akka.stream.impl.Ast
import org.reactivestreams.api.Producer
import scala.concurrent.duration._

// FIXME is Processor the right naming here?
object ProcessorGenerator {
  def apply(settings: GeneratorSettings)(implicit context: ActorRefFactory): ProcessorGenerator =
    new ActorBasedProcessorGenerator(settings, context)
}

trait ProcessorGenerator {
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
  private[akka] def produce[T](f: () ⇒ T): Producer[T]
}

// FIXME default values? Should we have an extension that reads from config?
case class GeneratorSettings(
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
}

