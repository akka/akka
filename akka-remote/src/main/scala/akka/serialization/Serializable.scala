/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import org.codehaus.jackson.map.ObjectMapper

import com.google.protobuf.Message

import reflect.Manifest

import java.io.{StringWriter, ByteArrayOutputStream, ObjectOutputStream}

import sjson.json.{Serializer=>SJSONSerializer}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializable {
  def toBytes: Array[Byte]
}

/**
 * Serialization protocols.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Serializable {

  /**
   * Example on how to use the SBinary serialization protocol:
   * <pre>
   * case class User(val usernamePassword: Tuple2[String, String],
   *                 val email: String,
   *                 val age: Int)
   *   extends Serializable.SBinary[User] {
   *   def this() = this(null, null, 0)
   *   import sbinary.DefaultProtocol._
   *   implicit object UserFormat extends Format[User] {
   *     def reads(in : Input) = User(
   *       read[Tuple2[String, String]](in),
   *       read[String](in),
   *       read[Int](in))
   *     def writes(out: Output, value: User) = {
   *       write[Tuple2[String, String]](out, value.usernamePassword)
   *       write[String](out, value.email)
   *       write[Int](out, value.age)
   *     }
   *   }
   *   def fromBytes(bytes: Array[Byte]) = fromByteArray[User](bytes)
   *   def toBytes: Array[Byte] = toByteArray(this)
   * }
   * </pre>
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait SBinary[T <: AnyRef] extends Serializable {
    def fromBytes(bytes: Array[Byte]): T
    def toBytes: Array[Byte]
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait JSON extends Serializable {
    def toJSON: String
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  abstract class JavaJSON extends JSON {

    def toJSON: String = {
      val out = new StringWriter
      val mapper = new ObjectMapper
      mapper.writeValue(out, this)
      out.close
      out.toString
    }

    def toBytes: Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val out = new ObjectOutputStream(bos)
      val mapper = new ObjectMapper
      mapper.writeValue(out, this)
      out.close
      bos.toByteArray
    }
  }

  /**
   * case class Address(street: String, city: String, zip: String)
   *   extends ScalaJSON[Address] {
   *
   *   implicit val AddressFormat: Format[Address] =
   *     asProduct3("street", "city", "zip")(Address)(Address.unapply(_).get)
   *
   *   import dispatch.json._
   *   import sjson.json._
   *   import sjson.json.JsonSerialization._
   *
   *   def toJSON: String = JsValue.toJson(tojson(this))
   *   def toBytes: Array[Byte] = tobinary(this)
   *   def fromBytes(bytes: Array[Byte]): Address = frombinary[Address](bytes)
   *   def fromJSON(js: String): Address = fromjson[Address](Js(js))
   * }
   *
   * val a = Address(...)
   * val js = tojson(a)
   * val add = fromjson[Address](js)
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait ScalaJSON[T] extends JSON {
    def toJSON: String
    def fromJSON(js: String): T
    def toBytes: Array[Byte]
    def fromBytes(bytes: Array[Byte]): T
  }
}
