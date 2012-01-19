/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel

import org.apache.camel.CamelContext

import akka.util.ReflectiveAccess.getObjectFor
import akka.camel.migration.Migration._

/**
 * Module for reflective access to akka-camel-typed.
 *
 * @author Martin Krasser
 */
//TODO: investigate what's this object for
private[camel] object TypedCamelAccess {
  val loader = getClass.getClassLoader

  object TypedCamelModule {

    type TypedCamelObject = {
      def onCamelContextInit(context: CamelContext): Unit
      def onCamelServiceStart(service: Camel): Unit
      def onCamelServiceStop(service: Camel): Unit
    }

    val typedCamelObject: Option[TypedCamelObject] =
      getObjectFor("akka.camel.TypedCamel$", loader)  match {
        case Right(value) => Some(value)
        case Left(exception) =>
          EventHandler.debug(this, exception.toString)
          None
      }
  }
}