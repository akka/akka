/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.util

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}

/** 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializer {  
  def deepClone[T <: AnyRef](obj: T): T = in(out(obj), Some(obj.getClass.asInstanceOf[Class[T]])).asInstanceOf[T]

  def out(obj: AnyRef): Array[Byte]

  def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object JavaSerializationSerializer extends Serializer {
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
object JSONSerializer extends Serializer {
  import org.codehaus.jackson.map.ObjectMapper

  private val json = new ObjectMapper

  def out(obj: AnyRef): Array[Byte] = {
    if (!json.canSerialize(obj.getClass)) throw new IllegalArgumentException("Can not serialize [" + obj + "] to JSON, please provide a JSON serializable object.")
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
