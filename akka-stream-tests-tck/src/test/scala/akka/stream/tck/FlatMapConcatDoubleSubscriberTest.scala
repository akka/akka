/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream.scaladsl.{ Sink, Source }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class FlatMapConcatDoubleSubscriberTest extends AkkaSubscriberBlackboxVerification[Int] {

  def createSubscriber(): Subscriber[Int] = {
    val subscriber = Promise[Subscriber[Int]]()
    Source
      .single(Source.fromPublisher(new Publisher[Int] {
        def subscribe(s: Subscriber[_ >: Int]): Unit =
          subscriber.success(s.asInstanceOf[Subscriber[Int]])
      }))
      .flatMapConcat(identity)
      .runWith(Sink.ignore)

    Await.result(subscriber.future, 1.second)
  }

  def createElement(element: Int): Int = element
}
