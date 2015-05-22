/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.xml

import scala.xml.NodeSeq
import org.scalatest.{ Matchers, WordSpec }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.{ Unmarshaller, Unmarshal }
import akka.http.scaladsl.model._
import HttpCharsets._
import MediaTypes._

class ScalaXmlSupportSpec extends WordSpec with Matchers with ScalatestRouteTest {
  import ScalaXmlSupport._

  "ScalaXmlSupport" should {
    "NodeSeqMarshaller should marshal xml snippets to `text/xml` content in UTF-8" in {
      marshal(<employee><nr>Ha“llo</nr></employee>) shouldEqual
        HttpEntity(ContentType(`text/xml`, `UTF-8`), "<employee><nr>Ha“llo</nr></employee>")
    }
    "nodeSeqUnmarshaller should unmarshal `text/xml` content in UTF-8 to NodeSeqs" in {
      Unmarshal(HttpEntity(`text/xml`, "<int>Hällö</int>")).to[NodeSeq].map(_.text) should evaluateTo("Hällö")
    }
    "nodeSeqUnmarshaller should reject `application/octet-stream`" in {
      Unmarshal(HttpEntity(`application/octet-stream`, "<int>Hällö</int>")).to[NodeSeq].map(_.text) should
        haveFailedWith(Unmarshaller.UnsupportedContentTypeException(nodeSeqContentTypeRanges: _*))
    }
  }
}
