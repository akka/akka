/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.testing

import akka.serialization.Serializer
import com.google.protobuf.Message
import org.codehaus.jackson.map.ObjectMapper
import java.io.{ ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream }
import akka.util.ClassLoaderObjectInputStream
import sjson.json._

class ProtobufSerializer extends Serializer {
  val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])

  def toBinary(obj: AnyRef): Array[Byte] = {
    if (!obj.isInstanceOf[Message]) throw new IllegalArgumentException(
      "Can't serialize a non-protobuf message using protobuf [" + obj + "]")
    obj.asInstanceOf[Message].toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader] = None): AnyRef = {
    if (!clazz.isDefined) throw new IllegalArgumentException(
      "Need a protobuf message class to be able to serialize bytes using protobuf")
    clazz.get.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[Message]
  }
}
object ProtobufSerializer extends ProtobufSerializer

class JavaJSONSerializer extends Serializer {
  private val mapper = new ObjectMapper

  def toBinary(obj: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    mapper.writeValue(out, obj)
    out.close
    bos.toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader] = None): AnyRef = {
    if (!clazz.isDefined) throw new IllegalArgumentException(
      "Can't deserialize JSON to instance if no class is provided")
    val in =
      if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes))
      else new ObjectInputStream(new ByteArrayInputStream(bytes))
    val obj = mapper.readValue(in, clazz.get).asInstanceOf[AnyRef]
    in.close
    obj
  }
}
object JavaJSONSerializer extends JavaJSONSerializer

class SJSONSerializer extends Serializer {

  def toBinary(obj: AnyRef): Array[Byte] =
    sjson.json.Serializer.SJSON.out(obj)

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], cl: Option[ClassLoader] = None): AnyRef = {
    if (!clazz.isDefined) throw new IllegalArgumentException(
      "Can't deserialize JSON to instance if no class is provided")

    import sjson.json.Serializer._
    val sj = new SJSON with DefaultConstructor { val classLoader = cl }
    sj.in(bytes, clazz.get.getName)
  }
}
object SJSONSerializer extends SJSONSerializer
