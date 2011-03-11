/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel

import org.apache.camel.CamelContext

import akka.util.ReflectiveAccess.getObjectFor

/**
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
      getObjectFor("akka.camel.TypedCamel$", loader)
  }
}