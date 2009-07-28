/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.serialization

import com.google.protobuf.Message
import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}
import reflect.{BeanProperty, Manifest}
import sbinary.DefaultProtocol
import org.codehaus.jackson.map.ObjectMapper
import com.twitter.commons.Json

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializer {
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

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Java extends Serializer {
    def deepClone(obj: AnyRef): AnyRef = in(out(obj), None)

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
  object Protobuf extends Serializer {
    def deepClone(obj: AnyRef): AnyRef = in(out(obj), Some(obj.getClass))

    def out(obj: AnyRef): Array[Byte] = {
      if (!obj.isInstanceOf[Message]) throw new IllegalArgumentException("Can't serialize a non-protobuf message using protobuf [" + obj + "]")
      obj.asInstanceOf[Message].toByteArray
    }
    
    def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
      if (!clazz.isDefined) throw new IllegalArgumentException("Need a protobuf message class to be able to serialize bytes using protobuf") 
      // TODO: should we cache this method lookup?
      val message = clazz.get.getDeclaredMethod("getDefaultInstance", EMPTY_CLASS_ARRAY: _*).invoke(null, EMPTY_ANY_REF_ARRAY: _*).asInstanceOf[Message]
      message.toBuilder().mergeFrom(bytes).build                                                                                  
    }

    // For Java
    def in(bytes: Array[Byte], clazz: Class[_]): AnyRef = {
      if (clazz == null) throw new IllegalArgumentException("Protobuf message can't be null")
      in(bytes, Some(clazz))
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object JavaJSON extends Serializer {
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
      if (!clazz.isDefined) throw new IllegalArgumentException("Can't deserialize JSON to instance if no class is provided")
      val in = new ObjectInputStream(new ByteArrayInputStream(bytes))
      val obj = mapper.readValue(in, clazz.get).asInstanceOf[AnyRef]
      in.close
      obj
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object ScalaJSON extends Serializer {
    def deepClone(obj: AnyRef): AnyRef = in(out(obj), None)

    def out(obj: AnyRef): Array[Byte] = Json.build(obj).toString.getBytes("UTF-8")

    def in(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = Json.parse(new String(bytes, "UTF-8")).asInstanceOf[AnyRef]
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object SBinary {
    import sbinary.DefaultProtocol._
    
    def deepClone[T <: AnyRef](obj: T)(implicit w : Writes[T], r : Reads[T]): T = in[T](out[T](obj), None)

    def out[T](t : T)(implicit bin : Writes[T]): Array[Byte] = toByteArray[T](t)

    def in[T](array : Array[Byte], clazz: Option[Class[T]])(implicit bin : Reads[T]): T = fromByteArray[T](array)
  }
}

