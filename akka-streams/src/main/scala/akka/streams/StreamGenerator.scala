package akka.streams

import asyncrx.api.{ Consumer, Producer, Processor }
import akka.streams.Operation.{ Pipeline, Source, Sink }

/**
 * An ImplementationFactory takes an immutable representation of an operation (or source, sink, or pipeline)
 * and implements a Processor, Consumer, or Producer from it or directly runs it (for a pipeline).
 */
trait StreamGenerator {
  def createProcessor[I, O](operation: Operation[I, O]): Processor[I, O]
  def createConsumer[I](sink: Sink[I]): Consumer[I]
  def createProducer[O](source: Source[O]): Producer[O]
  def runPipeline(pipeline: Pipeline[_]): Unit
}
