/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import java.io.{ ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream }

import org.apache.commons.io.input.ClassLoaderObjectInputStream

import com.google.protobuf.Message

import org.codehaus.jackson.map.ObjectMapper

import sjson.json.{ Serializer ⇒ SJSONSerializer }

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializer extends scala.Serializable {

  def deepClone(obj: AnyRef): AnyRef = obj match {
    case null ⇒ null
    case _    ⇒ fromBinary(toBinary(obj), Some(obj.getClass), Some(obj.getClass.getClassLoader))
  }

  def toBinary(obj: AnyRef): Array[Byte]
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader] = None): AnyRef
}

// For Java API
class SerializerFactory {
  import Serializer._
  def getJava: Java.type = Java
  def getJavaJSON: JavaJSON.type = JavaJSON
  def getScalaJSON: ScalaJSON.type = ScalaJSON
  def getProtobuf: Protobuf.type = Protobuf
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Serializer {
  val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])

  object NOOP extends NOOP
  class NOOP extends Serializer {
    def toBinary(obj: AnyRef): Array[Byte] = Array[Byte]()
    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader]): AnyRef = null: AnyRef
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Java extends Java
  trait Java extends Serializer {
    def toBinary(obj: AnyRef): Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val out = new ObjectOutputStream(bos)
      out.writeObject(obj)
      out.close
      bos.toByteArray
    }

    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader]): AnyRef = {
      val in =
        if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes))
        else if (clazz.isDefined) new ClassLoaderObjectInputStream(clazz.get.getClassLoader, new ByteArrayInputStream(bytes))
        else new ObjectInputStream(new ByteArrayInputStream(bytes))
      try {
        in.readObject
      } finally {
        in.close
      }
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Protobuf extends Protobuf
  trait Protobuf extends Serializer {
    def toBinary(obj: AnyRef): Array[Byte] = {
      if (!obj.isInstanceOf[Message]) throw new IllegalArgumentException(
        "Can't serialize a non-protobuf message using protobuf [" + obj + "]")
      obj.asInstanceOf[Message].toByteArray
    }

    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader]): AnyRef = clazz match {
      case None    ⇒ throw new IllegalArgumentException("Need a protobuf message class to be able to serialize bytes using protobuf")
      case Some(c) ⇒ c.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[Message]
    }

    def fromBinary(bytes: Array[Byte], clazz: Class[_]): AnyRef = {
      if (clazz eq null) throw new IllegalArgumentException("Protobuf message can't be null")
      fromBinary(bytes, Some(clazz))
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object JavaJSON extends JavaJSON
  trait JavaJSON extends Serializer {
    private val mapper = new ObjectMapper

    def toBinary(obj: AnyRef): Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val out = new ObjectOutputStream(bos)
      mapper.writeValue(out, obj)
      out.close
      bos.toByteArray
    }

    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader]): AnyRef = clazz match {
      case None ⇒ throw new IllegalArgumentException("Can't deserialize JSON to instance if no class is provided")
      case Some(c) ⇒
        val in =
          if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes))
          else new ClassLoaderObjectInputStream(c.getClassLoader, new ByteArrayInputStream(bytes))
        try {
          mapper.readValue(in, clazz.get).asInstanceOf[AnyRef]
        } finally {
          in.close
        }
    }

    def fromJSON(json: String, clazz: Class[_]): AnyRef = clazz match {
      case null ⇒ throw new IllegalArgumentException("Can't deserialize JSON to instance if no class is provided")
      case _    ⇒ mapper.readValue(json, clazz).asInstanceOf[AnyRef]
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  trait ScalaJSON {
    import sjson.json._

    var classLoader: Option[ClassLoader] = None

    def tojson[T](o: T)(implicit tjs: Writes[T]): JsValue = JsonSerialization.tojson(o)(tjs)

    def fromjson[T](json: JsValue)(implicit fjs: Reads[T]): T = JsonSerialization.fromjson(json)(fjs)

    def tobinary[T](o: T)(implicit tjs: Writes[T]): Array[Byte] = JsonSerialization.tobinary(o)(tjs)

    def frombinary[T](bytes: Array[Byte])(implicit fjs: Reads[T]): T = JsonSerialization.frombinary(bytes)(fjs)

    // backward compatibility
    // implemented using reflection based json serialization
    def toBinary(obj: AnyRef): Array[Byte] = SJSONSerializer.SJSON.out(obj)

    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = SJSONSerializer.SJSON.in(bytes)

    import scala.reflect.Manifest
    def fromJSON[T](json: String)(implicit m: Manifest[T]): AnyRef = {
      SJSONSerializer.SJSON.in(json)(m)
    }

    def fromBinary[T](bytes: Array[Byte])(implicit m: Manifest[T]): AnyRef = {
      SJSONSerializer.SJSON.in(bytes)(m)
    }
  }
  object ScalaJSON extends ScalaJSON
}

