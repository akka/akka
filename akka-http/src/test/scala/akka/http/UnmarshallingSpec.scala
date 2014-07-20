/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.stream.scaladsl.Flow
import org.scalatest.matchers.Matcher

import scala.xml.NodeSeq
import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import akka.actor.ActorSystem
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.http.unmarshalling.{ Unmarshalling, Unmarshaller }
import akka.http.model.MediaTypes._
import akka.http.model._

class UnmarshallingSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  implicit val system = ActorSystem(getClass.getSimpleName)
  val materializerSettings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  import system.dispatcher
  implicit val materializer = FlowMaterializer(materializerSettings)

  "The PredefinedFromEntityUnmarshallers." - {
    "stringUnmarshaller should unmarshal `text/plain` content in UTF-8 to Strings" in {
      Unmarshal(HttpEntity("Hällö")).to[String] should evaluateTo("Hällö")
    }
    "charArrayUnmarshaller should unmarshal `text/plain` content in UTF-8 to char arrays" in {
      Unmarshal(HttpEntity("Hällö")).to[Array[Char]] should evaluateTo("Hällö".toCharArray)
    }
    //    "nodeSeqUnmarshaller should unmarshal `text/xml` content in UTF-8 to NodeSeqs" in {
    //      Unmarshal(HttpEntity(`text/xml`, "<int>Hällö</int>")).to[NodeSeq].map(_.map(_.text)) shouldEqual "Hällö"
    //    }
  }

  "The MultipartUnmarshallers." - {
    "multipartContentUnmarshaller should correctly unmarshal 'multipart/mixed' content with" - {
      "one empty part" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC",
          """--XYZABC
            |--XYZABC--""".stripMargin)).to[MultipartContent] should haveParts()
      }
    }
  }

  override def afterAll() = system.shutdown()

  def evaluateTo[T](value: T): Matcher[Future[Unmarshalling[T]]] =
    equal(value).matcher[T] compose { unmarshallingFuture ⇒
      Await.result(unmarshallingFuture, 1.second) match {
        case Unmarshalling.Success(x) ⇒ x
        case x                        ⇒ fail(x.toString)
      }
    }

  def haveParts[T <: MultipartParts](parts: BodyPart*): Matcher[Future[Unmarshalling[T]]] =
    equal(parts).matcher[Seq[BodyPart]] compose { unmarshallingFuture ⇒
      Await.result(unmarshallingFuture, 1.second) match {
        case Unmarshalling.Success(x) ⇒ Await.result(Flow(x.parts).grouped(100).toFuture(materializer), 1.second)
        case x                        ⇒ fail(x.toString)
      }
    }
}
