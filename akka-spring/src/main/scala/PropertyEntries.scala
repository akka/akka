package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder

import scala.collection.mutable._

/**
* Simple container for Properties
* @author <a href="johan.rask@jayway.com">Johan Rask</a>
*/
class PropertyEntries {

   var entryList:ListBuffer[PropertyEntry] = ListBuffer[PropertyEntry]()

   def add(entry:PropertyEntry) = {
                entryList.append(entry)
  }
}
