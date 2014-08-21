/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.http.model.headers.{ ContentDispositionTypes, `Content-Disposition` }
import akka.util.ByteString

import org.scalatest.{ Inside, Matchers, WordSpec }

class MultipartContentSpec extends WordSpec with Matchers with Inside {
  "BodyPart" should {
    val data = HttpEntity(ByteString("data"))

    "be matched with NamedBodyPart extractor if it has a name" in {
      val part = BodyPart(data, "name")
      inside(part) {
        case NamedBodyPart("name", entity, _) ⇒ entity should equal(data)
      }
    }

    "not be matched with NamedBodyPart extractor if it has no name" in {
      val part = BodyPart(data)
      inside(part) {
        case NamedBodyPart(name, entity, _) ⇒ fail(s"Shouldn't match but did match with name '$name'")
        case _                              ⇒
      }
    }

    "be matched with FileBodyPart extractor if it contains a file" in {
      val part = BodyPart(FormFile("data.txt", data), "name")
      inside(part) {
        case FileBodyPart("name", "data.txt", entity, _) ⇒ entity should equal(data)
      }
    }

    "be matched with FileBodyPart extractor if it contains a file but no name" in {
      val part = BodyPart(data, `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("filename" -> "data.txt")) :: Nil)

      inside(part) {
        case FileBodyPart("", "data.txt", entity, _) ⇒ entity should equal(data)
      }
    }

    "not be matched with NamedBodyPart extractor if it doesn't contains a file" in {
      val part = BodyPart(data)
      inside(part) {
        case FileBodyPart(name, filename, entity, _) ⇒ fail(s"Shouldn't match but did match with name '$name' and filename '$filename'")
        case _                                       ⇒
      }
    }
  }
}
