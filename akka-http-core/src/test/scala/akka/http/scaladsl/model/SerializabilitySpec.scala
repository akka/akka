/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.io._
import headers._
import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.matchers.{ MatchResult, Matcher }
import scala.util.Try
import akka.util.ByteString

class SerializabilitySpec extends WordSpec with Matchers {

  "HttpRequests" should {
    "be serializable" when {
      pending
      "empty" in {
        HttpRequest() should beSerializable
      }
      "with complex URI" in {
        HttpRequest(uri = Uri("/test?blub=28&x=5+3")) should beSerializable
      }
      "with content type" in {
        HttpRequest().withEntity(HttpEntity(ContentTypes.`application/json`, ByteString.empty)) should beSerializable
      }
      "with accepted media types" in {
        HttpRequest().withHeaders(Accept(MediaTypes.`application/json`)) should beSerializable
      }
      "with accept-charset" in {
        HttpRequest().withHeaders(`Accept-Charset`(HttpCharsets.`UTF-16`)) should beSerializable
        HttpRequest().withHeaders(`Accept-Charset`(HttpCharset.custom("utf8"))) should beSerializable
      }
      "with accepted encodings" in {
        HttpRequest().withHeaders(`Accept-Encoding`(HttpEncodings.chunked)) should beSerializable
        HttpRequest().withHeaders(`Accept-Encoding`(HttpEncoding.custom("test"))) should beSerializable
      }
    }
  }

  "HttpResponse" should {
    "be serializable" when {
      "empty" in {
        pending
        HttpResponse() should beSerializable
      }
    }
  }

  "Header values" should {
    "be serializable" when {
      "Cache" in { CacheDirectives.`no-store` should beSerializable }
      "DateTime" in { DateTime.now should beSerializable }
      "Charsets" in {
        tryToSerialize(HttpCharsets.`UTF-16`).nioCharset shouldEqual HttpCharsets.`UTF-16`.nioCharset
      }
      "LanguageRange" in {
        Language("a", "b") should beSerializable
        LanguageRange.`*` should beSerializable
      }
      "MediaRange" in { MediaRanges.`application/*` should beSerializable }
    }
  }

  def beSerializable: Matcher[AnyRef] = Matcher[AnyRef] { value ⇒
    val result = Try(tryToSerialize(value))
    MatchResult(
      result.isSuccess,
      "Failed with " + result,
      "Was unexpectly successful and returned " + result)
  }

  def tryToSerialize[T](obj: T): T = {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(obj)
    oos.close()
    // make sure to use correct class loader
    val loader = classOf[HttpRequest].getClassLoader
    val ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray)) {
      override def resolveClass(desc: ObjectStreamClass): Class[_] =
        Class.forName(desc.getName, false, loader)
    }

    val rereadObj = ois.readObject()
    rereadObj == obj
    rereadObj.toString == obj.toString
    rereadObj.asInstanceOf[T]
  }
}
