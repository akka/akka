/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package marshalling
package sse

import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.testkit.RouteTest
import akka.http.scaladsl.testkit.TestFrameworkInterface.Scalatest
import akka.stream.scaladsl.{ Sink, Source }
import org.scalatest.{ Matchers, WordSpec }
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

final class EventStreamMarshallingSpec extends WordSpec with Matchers with RouteTest with Scalatest {
  import Directives._
  import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

  "A source of ServerSentEvents" should {
    "be marshallable to a HTTP response" in {
      val events = 1.to(666).map(n ⇒ ServerSentEvent(n.toString))
      val route = complete(Source(events))
      Get() ~> route ~> check {
        mediaType shouldBe `text/event-stream`
        Await.result(responseEntity.dataBytes.runWith(Sink.seq), 3.seconds) shouldBe events.map(_.encode)
      }
    }
  }
}
