/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher

class PrefixAndTailTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val futureTailSource = Source(iterable(elements)).prefixAndTail(0).map { case (_, tail) ⇒ tail }.runWith(Sink.head)
    val tailSource = Await.result(futureTailSource, 3.seconds)
    tailSource.runWith(Sink.asPublisher(false))
  }

}
