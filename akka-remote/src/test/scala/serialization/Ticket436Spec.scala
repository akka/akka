package se.scalablesolutions.akka.actor.serialization


import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.serialization.Serializable.ScalaJSON
import scala.reflect._
import scala.annotation.target._
import sjson.json.JSONTypeHint

@BeanInfo class MyJsonObject(val key: String, 
  @(JSONTypeHint @field)(value = classOf[Int])
  val map: Map[String, Int], 
  val standAloneInt: Int) extends ScalaJSON {
  private def this() = this(null, null, -1)
  override def toString(): String = try {
    val mapValue: Int = map.getOrElse(key, -1)
    println("Map value: %s".format(mapValue.asInstanceOf[AnyRef].getClass))
    "Key: %s, Map value: %d, Stand Alone Int: %d".format(key, mapValue, standAloneInt)
  } catch {
    case e: ClassCastException => e.getMessage
    case _ => "Unknown error"
  }
}

@RunWith(classOf[JUnitRunner])
class Ticket436Spec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  describe("Serialization of Maps containing Int") {
    it("should be able to serialize and de-serialize preserving the data types of the Map") {
      val key: String = "myKey"
      val value: Int = 123
      val standAloneInt: Int = 35
      val message = new MyJsonObject(key, Map(key -> value), standAloneInt)

      val json = message.toJSON
      val copy = Serializer.ScalaJSON.fromJSON[MyJsonObject](json)
      copy.asInstanceOf[MyJsonObject].map.get("myKey").get.isInstanceOf[Int] should equal(true)
    }
  }
}
