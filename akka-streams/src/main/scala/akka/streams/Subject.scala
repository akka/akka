package akka.streams

import rx.async.spi.Subscriber
import rx.async.api.Producer

// probably not a good idea since this won't enable automatic backpressure
trait Subject[T] extends Producer[T] with Subscriber[T]
object Subject {
  def apply[T](): Subject[T] = ???
}
