package akka

import rx.async.api.{ Consumer, Producer }

package object streams {
  implicit class LinkProducer[I](val producer: Producer[I]) extends AnyVal {
    // this probably belongs somewhere in the API but is probably not figured out under which name
    def link(consumer: Consumer[I]): Unit = producer.getPublisher.subscribe(consumer.getSubscriber)
  }
}
