package akka.serialization

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.serialization.Serializable.ScalaJSON

object Serializables {
  import DefaultProtocol._
  import JsonSerialization._

  case class Shop(store: String, item: String, price: Int) extends
    ScalaJSON[Shop] {
    implicit val ShopFormat: sjson.json.Format[Shop] =
      asProduct3("store", "item", "price")(Shop)(Shop.unapply(_).get)

    def toJSON: String = JsValue.toJson(tojson(this))
    def toBytes: Array[Byte] = tobinary(this)
    def fromBytes(bytes: Array[Byte]) = frombinary[Shop](bytes)
    def fromJSON(js: String) = fromjson[Shop](Js(js))
  }

  case class MyMessage(val id: String, val value: Tuple2[String, Int])
  implicit val MyMessageFormat: sjson.json.Format[MyMessage] =
    asProduct2("id", "value")(MyMessage)(MyMessage.unapply(_).get)

  case class MyJsonObject(val key: String, val map: Map[String, Int],
    val standAloneInt: Int) extends ScalaJSON[MyJsonObject] {
    implicit val MyJsonObjectFormat: sjson.json.Format[MyJsonObject] =
      asProduct3("key", "map", "standAloneInt")(MyJsonObject)(MyJsonObject.unapply(_).get)

    def toJSON: String = JsValue.toJson(tojson(this))
    def toBytes: Array[Byte] = tobinary(this)
    def fromBytes(bytes: Array[Byte]) = frombinary[MyJsonObject](bytes)
    def fromJSON(js: String) = fromjson[MyJsonObject](Js(js))
  }
}

@RunWith(classOf[JUnitRunner])
class ScalaJSONSerializableSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  import Serializables._
  describe("Serialization of case classes") {
    it("should be able to serialize and de-serialize") {
      val s = Shop("Target", "cooker", 120)
      s.fromBytes(s.toBytes) should equal(s)
      s.fromJSON(s.toJSON) should equal(s)

      val key: String = "myKey"
      val value: Int = 123
      val standAloneInt: Int = 35
      val message = MyJsonObject(key, Map(key -> value), standAloneInt)
      message.fromBytes(message.toBytes) should equal(message)
      message.fromJSON(message.toJSON) should equal(message)
    }
  }
}
