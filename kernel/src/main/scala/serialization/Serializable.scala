/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.serialization


import com.twitter.commons.Json
import java.io.{StringWriter, ByteArrayOutputStream, ObjectOutputStream}
import reflect.Manifest
import sbinary.DefaultProtocol

object Serializable {
  trait Protobuf {

  }

  trait SBinary[T] extends DefaultProtocol {
    def toBytes: Array[Byte] = toByteArray(this)
    def getManifest: Manifest[T] = Manifest.singleType(this.asInstanceOf[T])
  }

  trait JavaJSON {
    private val mapper = new org.codehaus.jackson.map.ObjectMapper

    def toJSON: String = {
      val out = new StringWriter
      mapper.writeValue(out, obj)
      out.close
      out.toString
    }

    def toBytes: Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val out = new ObjectOutputStream(bos)
      mapper.writeValue(out, obj)
      out.close
      bos.toByteArray
    }
  }

  trait ScalaJSON {
    def toJSON: String = {
      Json.build(obj).toString.getBytes("UTF-8")
    }

    def toBytes: Array[Byte] = {
      Json.build(obj).toString.getBytes("UTF-8")
    }
  }
}