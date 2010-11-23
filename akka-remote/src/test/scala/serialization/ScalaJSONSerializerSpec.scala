package akka.serialization

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.serialization.Serializer.ScalaJSON
/*
object Protocols {
  import sjson.json.DefaultProtocol._
  case class Shop(store: String, item: String, price: Int)
  implicit val ShopFormat: sjson.json.Format[Shop] =
    asProduct3("store", "item", "price")(Shop)(Shop.unapply(_).get)

  case class MyMessage(val id: String, val value: Tuple2[String, Int])
  implicit val MyMessageFormat: sjson.json.Format[MyMessage] =
    asProduct2("id", "value")(MyMessage)(MyMessage.unapply(_).get)

  case class MyJsonObject(val key: String, val map: Map[String, Int],
    val standAloneInt: Int)
  implicit val MyJsonObjectFormat: sjson.json.Format[MyJsonObject] =
    asProduct3("key", "map", "standAloneInt")(MyJsonObject)(MyJsonObject.unapply(_).get)
}

@RunWith(classOf[JUnitRunner])
class ScalaJSONSerializerSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  import Protocols._
  import ScalaJSON._
  describe("Serialization of case classes") {
    it("should be able to serialize and de-serialize") {
      val s = Shop("Target", "cooker", 120)
      fromjson[Shop](tojson(s)) should equal(s)
      frombinary[Shop](tobinary(s)) should equal(s)

      val o = MyMessage("dg", ("akka", 100))
      fromjson[MyMessage](tojson(o)) should equal(o)
      frombinary[MyMessage](tobinary(o)) should equal(o)

      val key: String = "myKey"
      val value: Int = 123
      val standAloneInt: Int = 35
      val message = MyJsonObject(key, Map(key -> value), standAloneInt)
      fromjson[MyJsonObject](tojson(message)) should equal(message)
    }
  }
}
*/
