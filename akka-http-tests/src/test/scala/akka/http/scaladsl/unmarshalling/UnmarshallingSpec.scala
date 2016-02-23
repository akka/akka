/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.unmarshalling

import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import akka.http.scaladsl.testkit.ScalatestUtils
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model._

class UnmarshallingSpec extends FreeSpec with Matchers with BeforeAndAfterAll with ScalatestUtils {
  implicit val system = ActorSystem(getClass.getSimpleName)
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  "The PredefinedFromEntityUnmarshallers." - {
    "stringUnmarshaller should unmarshal `text/plain` content in UTF-8 to Strings" in {
      Unmarshal(HttpEntity("Hällö")).to[String] should evaluateTo("Hällö")
    }
    "charArrayUnmarshaller should unmarshal `text/plain` content in UTF-8 to char arrays" in {
      Unmarshal(HttpEntity("árvíztűrő ütvefúrógép")).to[Array[Char]] should evaluateTo("árvíztűrő ütvefúrógép".toCharArray)
    }
  }
  override def afterAll() = system.terminate()
}
