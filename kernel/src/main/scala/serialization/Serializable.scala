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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Serializable {

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
  trait JavaJSON[T] extends JSON[T]{
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

  /**
   * <pre>
   *   import sbinary.DefaultProtocol._
   *   def fromBytes(bytes: Array[Byte]) = fromByteArray[String](bytes)
   *   def toBytes: Array[Byte] =          toByteArray(body)
   * </pre>
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait SBinary extends Serializable {
    def fromBytes(bytes: Array[Byte])
    def toBytes: Array[Byte]
  }
}