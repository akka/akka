package akka.streams

import rx.async.api.{ Consumer, Producer, Processor }
import akka.actor.ActorRefFactory
import akka.streams.impl._
import Operation._

case class ActorBasedImplementationSettings(refFactory: ActorRefFactory, fanOutBufferSize: Int = 1)

class ActorBasedImplementationFactory(settings: ActorBasedImplementationSettings) extends ImplementationFactory {
  def toProcessor[I, O](operation: Operation[I, O]): Processor[I, O] = Implementation.toProcessor(operation, settings)
  def toProducer[O](source: Source[O]): Producer[O] = Implementation.toProducer(source, settings)
  def toConsumer[I](sink: Sink[I]): Consumer[I] = ???
  def runPipeline(pipeline: Pipeline[_]): Unit = Implementation.runPipeline(pipeline, settings)
}
