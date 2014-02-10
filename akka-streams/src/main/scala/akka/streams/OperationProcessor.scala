package akka.streams

import rx.async.api.{ Consumer, Producer, Processor }
import akka.actor.ActorRefFactory
import akka.streams.impl._
import Operation._

case class ProcessorSettings(ctx: ActorRefFactory, constructFanOutBox: () ⇒ FanOutBox)

/**
 * An operation processor takes an immutable representation of an operation (or source, sink, or pipeline)
 * and implements a Processor, Consumer, or Producer from it or directly runs it (for a pipeline).
 */
object OperationProcessor {
  def apply[I, O](operation: Operation[I, O], settings: ProcessorSettings): Processor[I, O] =
    ProcessorImplementation.operation(operation, settings)

  def apply[I](sink: Sink[I], settings: ProcessorSettings): Consumer[I] = ???
  def apply[O](source: Source[O], settings: ProcessorSettings): Producer[O] = ???
  def apply(pipeline: Pipeline[_], settings: ProcessorSettings): Unit =
    ProcessorImplementation.pipeline(pipeline, settings)
}
