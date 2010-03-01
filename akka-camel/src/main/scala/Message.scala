/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import org.apache.camel.{Message => CamelMessage}
import org.apache.camel.impl.DefaultCamelContext

import scala.collection.jcl.{Map => MapWrapper}

/**
 * @author Martin Krasser
 */
class Message(val body: Any, val headers: Map[String, Any]) {

  def this(body: Any) = this(body, Map.empty)

  def bodyAs[T](clazz: Class[T]): T = Message.converter.mandatoryConvertTo[T](clazz, body)

}

/**
 * @author Martin Krasser
 */
object Message {

  val converter = new DefaultCamelContext().getTypeConverter

  def apply(body: Any) = new Message(body)

  def apply(body: Any, headers: Map[String, Any]) = new Message(body, headers)

  def apply(cm: CamelMessage) =
    new Message(cm.getBody, Map.empty ++ MapWrapper[String, AnyRef](cm.getHeaders).elements)

}

/**
 * @author Martin Krasser
 */
class CamelMessageWrapper(val cm: CamelMessage) {

  def from(m: Message): CamelMessage = {
    cm.setBody(m.body)
    for (h <- m.headers) {
      cm.getHeaders.put(h._1, h._2.asInstanceOf[AnyRef])
    }
    cm
  }

}

/**
 * @author Martin Krasser
 */
object CamelMessageWrapper {

  implicit def wrapCamelMessage(cm: CamelMessage): CamelMessageWrapper = new CamelMessageWrapper(cm)

}