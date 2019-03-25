/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream.scaladsl._
import org.reactivestreams.Subscriber

class ForeachSinkSubscriberTest extends AkkaSubscriberBlackboxVerification[Int] {

  override def createSubscriber(): Subscriber[Int] =
    Flow[Int]
      .to(Sink.foreach { _ =>
      })
      .runWith(Source.asSubscriber)

  override def createElement(element: Int): Int = element
}
