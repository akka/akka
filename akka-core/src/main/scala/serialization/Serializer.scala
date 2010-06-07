/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.serialization

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}

import org.apache.commons.io.input.ClassLoaderObjectInputStream

import com.google.protobuf.Message

import org.codehaus.jackson.map.ObjectMapper

import sjson.json.{Serializer => SJSONSerializer}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializer {
  var classLoader: Option[ClassLoader] = None

  def deepClone(obj: AnyRef): AnyRef

  def out(obj: AnyRef): Array[Byte]

  def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef
}

// For Java API
class SerializerFactory {
  import Serializer._
  def getJava: Java.type = Java
  def getJavaJSON: JavaJSON.type = JavaJSON
  def getScalaJSON: ScalaJSON.type = ScalaJSON
  def getSBinary: SBinary.type = SBinary
  def getProtobuf: Protobuf.type = Protobuf
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Serializer {
  val EMPTY_CLASS_ARRAY = Array[Class[_]]()
  val EMPTY_ANY_REF_ARRAY = Array[AnyRef]()

  object NOOP extends NOOP
  class NOOP extends Serializer {
    def deepClone(obj: AnyRef): AnyRef = obj
    def out(obj: AnyRef): Array[Byte] = obj.asInstanceOf[Array[Byte]]
    def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = bytes
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Java extends Java
  trait Java extends Serializer {
    def deepClone(obj: AnyRef): AnyRef = in(out(obj), None)

    def out(obj: AnyRef): Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val out = new ObjectOutputStream(bos)
      out.writeObject(obj)
      out.close
      bos.toByteArray
    }

    def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
      val in =
        if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes))
        else new ObjectInputStream(new ByteArrayInputStream(bytes))
      val obj = in.readObject
      in.close
      obj
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Protobuf extends Protobuf
  trait Protobuf extends Serializer {
    def deepClone(obj: AnyRef): AnyRef = in(out(obj), Some(obj.getClass))

    def out(obj: AnyRef): Array[Byte] = {
      if (!obj.isInstanceOf[Message]) throw new IllegalArgumentException(
        "Can't serialize a non-protobuf message using protobuf [" + obj + "]")
      obj.asInstanceOf[Message].toByteArray
    }

    def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
      if (!clazz.isDefined) throw new IllegalArgumentException(
        "Need a protobuf message class to be able to serialize bytes using protobuf")
      // TODO: should we cache this method lookup?
      val message = clazz.get.getDeclaredMethod(
        "getDefaultInstance", EMPTY_CLASS_ARRAY: _*).invoke(null, EMPTY_ANY_REF_ARRAY: _*).asInstanceOf[Message]
      message.toBuilder().mergeFrom(bytes).build
    }

    def in(bytes: Array[Byte], clazz: Class[_]): AnyRef = {
      if (clazz eq null) throw new IllegalArgumentException("Protobuf message can't be null")
      in(bytes, Some(clazz))
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object JavaJSON extends JavaJSON
  trait JavaJSON extends Serializer {
    private val mapper = new ObjectMapper

    def deepClone(obj: AnyRef): AnyRef = in(out(obj), Some(obj.getClass))

    def out(obj: AnyRef): Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val out = new ObjectOutputStream(bos)
      mapper.writeValue(out, obj)
      out.close
      bos.toByteArray
    }

    def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
      if (!clazz.isDefined) throw new IllegalArgumentException(
        "Can't deserialize JSON to instance if no class is provided")
      val in =
        if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes))
        else new ObjectInputStream(new ByteArrayInputStream(bytes))
      val obj = mapper.readValue(in, clazz.get).asInstanceOf[AnyRef]
      in.close
      obj
    }

    def in(json: String, clazz: Class[_]): AnyRef = {
      if (clazz eq null) throw new IllegalArgumentException("Can't deserialize JSON to instance if no class is provided")
      mapper.readValue(json, clazz).asInstanceOf[AnyRef]
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object ScalaJSON extends ScalaJSON
  trait ScalaJSON extends Serializer {
    def deepClone(obj: AnyRef): AnyRef = in(out(obj), None)

    def out(obj: AnyRef): Array[Byte] = SJSONSerializer.SJSON.out(obj)

    // FIXME set ClassLoader on SJSONSerializer.SJSON
    def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = SJSONSerializer.SJSON.in(bytes)

    import scala.reflect.Manifest
    def in[T](json: String)(implicit m: Manifest[T]): AnyRef = {
      SJSONSerializer.SJSON.in(json)(m)
    }

    def in[T](bytes: Array[Byte])(implicit m: Manifest[T]): AnyRef = {
      SJSONSerializer.SJSON.in(bytes)(m)
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object SBinary extends SBinary
  class SBinary {
    import sbinary._
    import sbinary.Operations._
    import sbinary.DefaultProtocol._

    var classLoader: Option[ClassLoader] = None

    def deepClone[T <: AnyRef](obj: T)(implicit w : Writes[T], r : Reads[T]): T = in[T](out[T](obj), None)

    def out[T](t : T)(implicit bin : Writes[T]): Array[Byte] = toByteArray[T](t)

    def in[T](array : Array[Byte], clazz: Option[Class[T]])(implicit bin : Reads[T]): T = fromByteArray[T](array)
  }
}

