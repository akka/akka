package akka

import rx.async.api.{ Processor, Consumer, Producer }
import akka.streams.Operation
import Operation.Pipeline

package object streams {
  implicit class LinkProducer[I](val producer: Producer[I]) extends AnyVal {
    // this probably belongs somewhere in the API but is probably not figured out under which name
    def link(consumer: Consumer[I]): Unit = producer.getPublisher.subscribe(consumer.getSubscriber)
  }
  implicit class RunPipeline(val pipeline: Pipeline[_]) extends AnyVal {
    def run()(implicit settings: ProcessorSettings): Unit = OperationProcessor(pipeline, settings)
  }
  implicit class ListOperation[I, O](val operation: Operation[I, O]) extends AnyVal {
    def run()(implicit settings: ProcessorSettings): Processor[I, O] = OperationProcessor(operation, settings)
  }
}
