/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import akka.config.Configuration
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer
import org.springframework.core.io.Resource
import java.util.Properties

/**
 * ConfiggyPropertyPlaceholderConfigurer. Property resource configurer for configgy files.
 */
class ConfiggyPropertyPlaceholderConfigurer extends PropertyPlaceholderConfigurer {

  /**
   * Sets the akka properties as local properties, leaves the location empty.
   * @param configgyResource akka.conf
   */
  override def setLocation(configgyResource: Resource) {
    if (configgyResource eq null) throw new IllegalArgumentException("Property 'config' must be set")
    val properties = loadSettings(configgyResource)
    setProperties(properties)
  }

  /**
   * Load the akka.conf and transform to properties.
   */
  private def loadSettings(configgyResource: Resource): Properties = {
    val config = Configuration.fromFile(configgyResource.getFile.getPath)
    val properties = new Properties()
    config.map.foreach { case (k, v) ⇒ properties.put(k, v.asInstanceOf[AnyRef]); println("(k,v)=" + k + ", " + v) }
    properties
  }

}
