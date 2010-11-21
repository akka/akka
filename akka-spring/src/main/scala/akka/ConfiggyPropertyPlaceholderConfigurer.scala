/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.spring

import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer
import org.springframework.core.io.Resource
import net.lag.configgy.Configgy
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
    val properties = loadAkkaConfig(configgyResource)
    setProperties(properties)
  }

  /**
   * Load the akka.conf and transform to properties.
   */
  private def loadAkkaConfig(configgyResource: Resource) : Properties = {
    Configgy.configure(configgyResource.getFile.getPath)
    val config = Configgy.config
    val properties = new Properties()
    config.asMap.foreach {case (k, v) => properties.put(k, v); println("(k,v)=" + k + ", " + v)}
    properties
  }

}
