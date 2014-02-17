package akka.streams

import rx.async.api.{ Consumer, Producer, Processor }
import akka.actor.ActorRefFactory
import akka.streams.impl._
import Operation._

case class ActorBasedImplementationSettings(refFactory: ActorRefFactory, constructFanOutBox: () ⇒ FanOutBox)

class ActorBasedImplementationFactory(settings: ActorBasedImplementationSettings) extends ImplementationFactory {
  def processor[I, O](operation: Operation[I, O]): Processor[I, O] = Implementation.forOperation(operation, settings)
  def producer[O](source: Source[O]): Producer[O] = Implementation.forSource(source, settings)
  def consumer[I](sink: Sink[I]): Consumer[I] = ???
  def runPipeline(pipeline: Pipeline[_]): Unit = Implementation.forPipeline(pipeline, settings)
}
