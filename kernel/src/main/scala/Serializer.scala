/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}

/** 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializer {  
  def out(obj: AnyRef): Array[Byte]
  def in(bytes: Array[Byte]): AnyRef
}

/** 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class JavaSerializationSerializer extends Serializer {
  def deepClone[T <: AnyRef](obj: T): T = in(out(obj)).asInstanceOf[T]
  
  def out(obj: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    out.writeObject(obj)
    out.close    
    bos.toByteArray
  }

  def in(bytes: Array[Byte]): AnyRef = {
    val in = new ObjectInputStream(new ByteArrayInputStream(bytes))
    val obj = in.readObject
    in.close
    obj
  }
}
