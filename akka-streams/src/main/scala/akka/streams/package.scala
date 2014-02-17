package akka

import rx.async.api.{ Processor, Consumer, Producer }

package object streams {
  import Operation.{ Sink, Source, Pipeline }

  implicit class LinkProducer[I](val producer: Producer[I]) extends AnyVal {
    // this probably belongs somewhere in the rx.streams.API but is probably not figured out under which name
    def link(consumer: Consumer[I]): Unit = producer.getPublisher.subscribe(consumer.getSubscriber)
  }
  implicit class CreateProcessor[I, O](operation: Operation[I, O])(implicit factory: ImplementationFactory) {
    def create(): Processor[I, O] = factory.processor(operation)
  }
  implicit class CreateProducer[O](source: Source[O])(implicit factory: ImplementationFactory) {
    def create(): Producer[O] = factory.producer(source)
  }
  implicit class CreateConsumer[I](sink: Sink[I])(implicit factory: ImplementationFactory) {
    def create(): Consumer[I] = factory.consumer(sink)
  }
  implicit class RunPipeline(val pipeline: Pipeline[_])(implicit factory: ImplementationFactory) {
    def run(): Unit = factory.runPipeline(pipeline)
  }
}
