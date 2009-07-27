/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.serialization

import org.codehaus.jackson.map.ObjectMapper
import com.google.protobuf.Message
import com.twitter.commons.Json
import reflect.Manifest
import sbinary.DefaultProtocol
import java.io.{StringWriter, ByteArrayOutputStream, ObjectOutputStream}

object SerializationProtocol {
  val SBINARY = 1
  val SCALA_JSON = 2
  val JAVA_JSON = 3
  val PROTOBUF = 4
  val JAVA = 5
  val AVRO = 6  
}

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
  trait JSON[T] extends Serializable {
    def body: T
    def toJSON: String
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  abstract class JavaJSON[T] extends JSON[T]{
    private val mapper = new ObjectMapper

    def toJSON: String = {
      val out = new StringWriter
      mapper.writeValue(out, body)
      out.close
      out.toString
    }

    def toBytes: Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val out = new ObjectOutputStream(bos)
      mapper.writeValue(out, body)
      out.close
      bos.toByteArray
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait ScalaJSON[T] extends JSON[T] {
    def toJSON: String = Json.build(body).toString
    def toBytes: Array[Byte] = Json.build(body).toString.getBytes
  }
  
  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait Protobuf extends Serializable {
    def toBytes: Array[Byte]
    def getSchema: Message
  }
}
