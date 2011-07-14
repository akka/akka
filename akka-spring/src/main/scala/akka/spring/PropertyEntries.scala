/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder

import scala.collection.mutable._

/**
 * Simple container for Properties
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 */
class PropertyEntries {
  var entryList: ListBuffer[PropertyEntry] = ListBuffer[PropertyEntry]()

  def add(entry: PropertyEntry) = {
    entryList.append(entry)
  }
}

/**
 * Represents a property element
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 */
class PropertyEntry {
  var name: String = _
  var value: String = null
  var ref: String = null

  override def toString(): String = {
    format("name = %s,value =  %s, ref = %s", name, value, ref)
  }
}

