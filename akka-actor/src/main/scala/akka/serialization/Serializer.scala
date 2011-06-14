package akka.serialization

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import java.io.{ ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream }
import akka.util.ClassLoaderObjectInputStream
import akka.actor.ActorRef

trait Serializer extends scala.Serializable {
  def toBinary(o: AnyRef): Array[Byte]
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]] = None, classLoader: Option[ClassLoader] = None): AnyRef
}

class JavaSerializer extends Serializer {
  def toBinary(o: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    out.writeObject(o)
    out.close()
    bos.toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]] = None,
                 classLoader: Option[ClassLoader] = None): AnyRef = {
    val in =
      if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes)) else
        new ObjectInputStream(new ByteArrayInputStream(bytes))
    val obj = in.readObject
    in.close()
    obj
  }
}

object JavaSerializer extends JavaSerializer
object Serializer {
  val defaultSerializerName = JavaSerializer.getClass.getName
}
