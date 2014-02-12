package akka

import rx.async.api.{ Processor, Consumer, Producer }
import akka.streams.Operation
import Operation.Pipeline

package object streams {
  implicit class LinkProducer[I](val producer: Producer[I]) extends AnyVal {
    // this probably belongs somewhere in the rx.streams.API but is probably not figured out under which name
    def link(consumer: Consumer[I]): Unit = producer.getPublisher.subscribe(consumer.getSubscriber)
  }
  implicit class CreateProcessor[I, O](operation: Operation[I, O])(implicit factory: ImplementationFactory) {
    def create(): Processor[I, O] = factory.processor(operation)
  }
  implicit class CreateProducer[O](producer: Producer[O])(implicit factory: ImplementationFactory) {
    def create(): Producer[O] = factory.producer(producer)
  }
  implicit class CreateConsumer[I](consumer: Consumer[I])(implicit factory: ImplementationFactory) {
    def create(): Consumer[I] = factory.consumer(consumer)
  }
  implicit class RunPipeline(val pipeline: Pipeline[_])(implicit factory: ImplementationFactory) {
    def run(): Unit = factory.runPipeline(pipeline)
  }
}
