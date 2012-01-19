/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.CamelContext
import akka.event.EventHandler

import akka.util.ReflectiveAccess.getObjectFor

/**
 * Module for reflective access to akka-camel-typed.
 *
 * @author Martin Krasser
 */
private[camel] object TypedCamelAccess {
  val loader = getClass.getClassLoader

  object TypedCamelModule {

    type TypedCamelObject = {
      def onCamelContextInit(context: CamelContext): Unit
      def onCamelServiceStart(service: CamelService): Unit
      def onCamelServiceStop(service: CamelService): Unit
    }

    val typedCamelObject: Option[TypedCamelObject] =
      getObjectFor("akka.camel.TypedCamel$", loader) match {
        case Right(value) ⇒ Some(value)
        case Left(exception) ⇒
          EventHandler.debug(this, exception.toString)
          None
      }
  }
}
