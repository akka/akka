/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import java.io.InputStream

import akka.stream.ActorAttributes
import akka.stream.scaladsl.{ Sink, StreamConverters }
import akka.util.ByteString
import org.reactivestreams.Publisher

class InputStreamSourceTest extends AkkaPublisherVerification[ByteString] {

  def createPublisher(elements: Long): Publisher[ByteString] = {
    StreamConverters.fromInputStream(() ⇒ new InputStream {
      @volatile var num = 0
      override def read(): Int = {
        num += 1
        num
      }
    }).withAttributes(ActorAttributes.dispatcher("akka.test.stream-dispatcher"))
      .take(elements)
      .runWith(Sink.asPublisher(false))
  }
}

