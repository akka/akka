/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.Done
import akka.http.scaladsl.{ Http, TestUtils }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import scala.concurrent.duration._
import akka.util.ByteString

import scala.concurrent.{ Await, Promise }

class EntityDiscardingSpec extends AkkaSpec {

  implicit val mat = ActorMaterializer()

  val testData = Vector.tabulate(200)(i ⇒ ByteString(s"row-$i"))

  "HttpRequest" should {

    "discard entity stream after .discardEntityBytes() call" in {

      val p = Promise[Done]()
      val s = Source
        .fromIterator[ByteString](() ⇒ testData.iterator)
        .alsoTo(Sink.onComplete(t ⇒ p.complete(t)))

      val req = HttpRequest(entity = HttpEntity(ContentTypes.`text/csv(UTF-8)`, s))
      val de = req.discardEntityBytes()

      p.future.futureValue should ===(Done)
      de.future.futureValue should ===(Done)
    }
  }

  "HttpResponse" should {

    "discard entity stream after .discardEntityBytes() call" in {

      val p = Promise[Done]()
      val s = Source
        .fromIterator[ByteString](() ⇒ testData.iterator)
        .alsoTo(Sink.onComplete(t ⇒ p.complete(t)))

      val resp = HttpResponse(entity = HttpEntity(ContentTypes.`text/csv(UTF-8)`, s))
      val de = resp.discardEntityBytes()

      p.future.futureValue should ===(Done)
      de.future.futureValue should ===(Done)
    }

    // TODO consider improving this by storing a mutable "already materialized" flag somewhere
    // TODO likely this is going to inter-op with the auto-draining as described in #18716
    "should not allow draining a second time" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val bound = Http().bindAndHandleSync(
        req ⇒
          HttpResponse(entity = HttpEntity(
            ContentTypes.`text/csv(UTF-8)`, Source.fromIterator[ByteString](() ⇒ testData.iterator))),
        host, port).futureValue

      try {

        val response = Http().singleRequest(HttpRequest(uri = s"http://$host:$port/")).futureValue

        val de = response.discardEntityBytes()
        de.future.futureValue should ===(Done)

        val de2 = response.discardEntityBytes()
        val secondRunException = intercept[IllegalStateException] { Await.result(de2.future, 3.seconds) }
        secondRunException.getMessage should include("Source cannot be materialized more than once")
      } finally bound.unbind().futureValue
    }
  }

}
