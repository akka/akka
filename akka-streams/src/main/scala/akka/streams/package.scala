package akka

import asyncrx.api.{ Processor, Consumer, Producer }
import akka.streams.Operation.{ FromFutureSource, FromProducerSource, FromIterableSource }
import scala.concurrent.Future

package object streams extends OperationApiImplicits {
  import Operation.{ Sink, Source, Pipeline }

  implicit class LinkProducer[I](val producer: Producer[I]) extends AnyVal {
    // this probably belongs somewhere in the rx.streams.API but is probably not figured out under which name
    def link(consumer: Consumer[I]): Unit = producer.getPublisher.subscribe(consumer.getSubscriber)
  }
  implicit class CreateProcessor[I, O](operation: Operation[I, O])(implicit factory: StreamGenerator) {
    def toProcessor(): Processor[I, O] = factory.toProcessor(operation)
  }
  implicit class CreateProducer[O](source: Source[O])(implicit factory: StreamGenerator) {
    def toProducer(): Producer[O] = factory.toProducer(source)
  }
  implicit class CreateConsumer[I](sink: Sink[I])(implicit factory: StreamGenerator) {
    def toConsumer(): Consumer[I] = factory.toConsumer(sink)
  }
  implicit class RunPipeline(val pipeline: Pipeline[_])(implicit factory: StreamGenerator) {
    def run(): Unit = factory.runPipeline(pipeline)
  }

  implicit class SourceFromFuture[T](val future: Future[T]) extends AnyVal {
    def toSource: Source[T] = Source(future)
  }
  implicit class SourceFromProducer[T](val producer: Producer[T]) extends AnyVal {
    def toSource: Source[T] = Source(producer)
  }
  implicit class SourceFromIterable[T](val iterable: Iterable[T]) extends AnyVal {
    def toSource: Source[T] = Source(iterable)
  }
}
