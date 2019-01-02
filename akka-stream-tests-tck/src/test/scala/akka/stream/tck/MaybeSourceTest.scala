/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import org.reactivestreams.Publisher
import akka.stream.scaladsl.{ Keep, Source, Sink }

class MaybeSourceTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val (p, pub) = Source.maybe[Int].toMat(Sink.asPublisher(false))(Keep.both).run()
    p success Some(1)
    pub
  }

  override def maxElementsFromPublisher(): Long = 1
}

