/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.rest

import java.io.OutputStream
import se.scalablesolutions.akka.serialization.Serializer
import javax.ws.rs.core.{MultivaluedMap, MediaType}
import javax.ws.rs.ext.{MessageBodyWriter, Provider}
import javax.ws.rs.Produces

/**
 * Writes Lists of JSON serializable objects.
 */
@Provider
@Produces(Array("application/json"))
class ListWriter extends MessageBodyWriter[List[_]] {

  def isWriteable(aClass: Class[_], 
                  aType: java.lang.reflect.Type, 
                  annotations: Array[java.lang.annotation.Annotation], 
                  mediaType: MediaType) =
    classOf[List[_]].isAssignableFrom(aClass) || aClass == ::.getClass

  def getSize(list: List[_], 
              aClass: Class[_], 
              aType: java.lang.reflect.Type, 
              annotations: Array[java.lang.annotation.Annotation], 
              mediaType: MediaType) = 
    -1L

  def writeTo(list: List[_],
              aClass: Class[_],
              aType: java.lang.reflect.Type,
              annotations: Array[java.lang.annotation.Annotation],
              mediaType: MediaType,
              stringObjectMultivaluedMap: MultivaluedMap[String, Object],
              outputStream: OutputStream): Unit =
    if (list.isEmpty) outputStream.write(" ".getBytes)
    else outputStream.write(Serializer.ScalaJSON.toBinary(list))
}
