/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.util

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}
import reflect.Manifest
import sbinary.DefaultProtocol
import org.codehaus.jackson.map.ObjectMapper
import com.twitter.commons.Json

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializer {
  def deepClone[T <: AnyRef](obj: T): T
  def out(obj: AnyRef): Array[Byte]
  def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object JavaSerializationSerializer extends Serializer {
  def deepClone[T <: AnyRef](obj: T): T = in(out(obj), None).asInstanceOf[T]

  def out(obj: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    out.writeObject(obj)
    out.close
    bos.toByteArray
  }

  def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    val in = new ObjectInputStream(new ByteArrayInputStream(bytes))
    val obj = in.readObject
    in.close
    obj
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object JavaJSONSerializer extends Serializer {
  private val json = new org.codehaus.jackson.map.ObjectMapper
  
  def deepClone[T <: AnyRef](obj: T): T = in(out(obj), Some(obj.getClass)).asInstanceOf[T]

  def out(obj: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    json.writeValue(out, obj)
    out.close
    bos.toByteArray
  }

  def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    if (!clazz.isDefined) throw new IllegalArgumentException("Can't deserialize JSON to instance if no class is provided")
    val in = new ObjectInputStream(new ByteArrayInputStream(bytes))
    val obj = json.readValue(in, clazz.get).asInstanceOf[AnyRef]
    in.close
    obj
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ScalaJSONSerializer extends Serializer {
  def deepClone[T <: AnyRef](obj: T): T = in(out(obj), None).asInstanceOf[T]

  def out(obj: AnyRef): Array[Byte] = {
    Json.build(obj).toString.getBytes("UTF-8")
  }

  def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    Json.parse(new String(bytes, "UTF-8")).asInstanceOf[AnyRef]
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object SBinarySerializer extends SBinarySerializer
trait SBinarySerializer extends DefaultProtocol {
  def in[T](t : T)(implicit bin : Writes[T], m: Manifest[T]): Pair[Array[Byte], Manifest[T]] =
    Pair(toByteArray(t), m)

  def out[T](array : Array[Byte], m: Manifest[T])(implicit bin : Reads[T]) =
    read[T](new ByteArrayInputStream(array))
}

