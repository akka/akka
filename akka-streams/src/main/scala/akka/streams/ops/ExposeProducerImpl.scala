package akka.streams.ops

import akka.streams.Operation.{ ExposeProducer, Source }
import rx.async.api.Producer

object ExposeProducerImpl {
  def apply[T](): OpInstance[Source[T], Producer[T]] = new OpInstance[Source[T], Producer[T]] {
    def handle(result: SimpleResult[Source[T]]): Result[Producer[T]] = ???
  }
}
