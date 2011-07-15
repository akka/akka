/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder

/**
 * Data container for dispatcher configuration data.
 * @author michaelkober
 */
class DispatcherProperties {
  var ref: String = ""
  var dispatcherType: String = ""
  var name: String = ""
  var threadPool: ThreadPoolProperties = _
  var aggregate = true

  /**
   * Sets the properties to the given builder.
   * @param builder bean definition builder
   */
  def setAsProperties(builder: BeanDefinitionBuilder) {
    builder.addPropertyValue("properties", this)
  }

  override def toString: String = {
    "DispatcherProperties[ref=" + ref +
      ", dispatcher-type=" + dispatcherType +
      ", name=" + name +
      ", threadPool=" + threadPool + "]"
  }
}

/**
 * Data container for thread pool configuration data.
 * @author michaelkober
 */
class ThreadPoolProperties {
  var queue = ""
  var bound = -1
  var capacity = -1
  var fairness = false
  var corePoolSize = -1
  var maxPoolSize = -1
  var keepAlive = -1L
  var rejectionPolicy = ""
  var mailboxCapacity = -1

  override def toString: String = {
    "ThreadPoolProperties[queue=" + queue +
      ", bound=" + bound +
      ", capacity=" + capacity +
      ", fairness=" + fairness +
      ", corePoolSize=" + corePoolSize +
      ", maxPoolSize=" + maxPoolSize +
      ", keepAlive=" + keepAlive +
      ", policy=" + rejectionPolicy +
      ", mailboxCapacity=" + mailboxCapacity + "]"
  }
}
