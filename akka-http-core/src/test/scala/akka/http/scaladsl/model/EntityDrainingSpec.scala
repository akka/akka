/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.util.concurrent.CompletableFuture

import akka.Done
import akka.actor.ActorSystem
import akka.japi.function
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.Promise
import scala.util.{ Failure, Success, Try }

class EntityDrainingSpec extends WordSpec with Matchers {

  implicit val sys = ActorSystem("test")
  implicit val mat = ActorMaterializer()

  val testData = Vector.tabulate(200)(i ⇒ ByteString(s"row-$i"))

  "HttpRequest" should {

    "drain entity stream after .discardEntityBytes() call" in {

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

    "drain entity stream after .discardEntityBytes() call" in {

      val p = Promise[Done]()
      val s = Source
        .fromIterator[ByteString](() ⇒ testData.iterator)
        .alsoTo(Sink.onComplete(t ⇒ p.complete(t)))

      val resp = HttpResponse(entity = HttpEntity(ContentTypes.`text/csv(UTF-8)`, s))
      val de = resp.discardEntityBytes()

      p.future.futureValue should ===(Done)
      de.future.futureValue should ===(Done)
    }
  }

}
