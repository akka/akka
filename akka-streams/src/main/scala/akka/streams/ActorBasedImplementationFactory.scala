package akka.streams

import rx.async.api
import api.{ Consumer, Processor }
import akka.actor.ActorRefFactory
import akka.streams.impl._
import Operation._

case class ActorBasedImplementationSettings(
  refFactory: ActorRefFactory,
  initialFanOutBufferSize: Int = 1,
  maxFanOutBufferSize: Int = 16,
  effectExecutor: EffectExecutor = PlainEffectExecutor) {
  require(initialFanOutBufferSize > 0, "initialFanOutBufferSize must be > 0")
  require(maxFanOutBufferSize > 0, "maxFanOutBufferSize must be > 0")
  require(initialFanOutBufferSize <= maxFanOutBufferSize,
    s"initialFanOutBufferSize($initialFanOutBufferSize) must be <= maxFanOutBufferSize($maxFanOutBufferSize)")
}

class ActorBasedImplementationFactory(settings: ActorBasedImplementationSettings) extends ImplementationFactory {
  def toProcessor[I, O](operation: Operation[I, O]): Processor[I, O] = Implementation.toProcessor(operation, settings)
  def toProducer[O](source: Source[O]): api.Producer[O] = Implementation.toProducer(source, settings)
  def toConsumer[I](sink: Sink[I]): Consumer[I] = ???
  def runPipeline(pipeline: Pipeline[_]): Unit = Implementation.runPipeline(pipeline, settings)
}
