/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.w3c.dom.Element
import org.springframework.util.xml.DomUtils

/**
 * Parser trait for custom namespace for Akka dispatcher configuration.
 * @author michaelkober
 */
trait DispatcherParser extends BeanParser {
  import AkkaSpringConfigurationTags._

  /**
   * Parses the given element and returns a DispatcherProperties.
   * @param element dom element to parse
   * @return configuration for the dispatcher
   */
  def parseDispatcher(element: Element): DispatcherProperties = {
    val properties = new DispatcherProperties()
    var dispatcherElement = element
    if (hasRef(element)) {
      val ref = element.getAttribute(REF)
      dispatcherElement = element.getOwnerDocument.getElementById(ref)
      if (dispatcherElement == null) {
        throw new IllegalArgumentException("Referenced dispatcher not found: '" + ref + "'")
      }
    }
    properties.name = mandatory(dispatcherElement, NAME)
    properties.dispatcherType = mandatory(dispatcherElement, TYPE)
    if (properties.dispatcherType == THREAD_BASED) {
      if ((dispatcherElement.getParentNode.getNodeName != "akka:typed-actor") &&
            (dispatcherElement.getParentNode.getNodeName != "typed-actor")) {
        throw new IllegalArgumentException("Thread based dispatcher must be nested in typed-actor element!")
      }
    }
    val threadPoolElement = DomUtils.getChildElementByTagName(dispatcherElement, THREAD_POOL_TAG);
    if (threadPoolElement != null) {
      if (properties.dispatcherType == REACTOR_BASED_SINGLE_THREAD_EVENT_DRIVEN ||
          properties.dispatcherType == THREAD_BASED) {
        throw new IllegalArgumentException("Element 'thread-pool' not allowed for this dispatcher type.")
      }
      val threadPoolProperties = parseThreadPool(threadPoolElement)
      properties.threadPool = threadPoolProperties
    }
    properties
}

  /**
   * Parses the given element and returns a ThreadPoolProperties.
   * @param element dom element to parse
   * @return configuration for the thread pool
   */
  def parseThreadPool(element: Element): ThreadPoolProperties = {
    val properties = new ThreadPoolProperties()
    properties.queue = element.getAttribute(QUEUE)
    if (element.hasAttribute(CAPACITY)) {
      properties.capacity = element.getAttribute(CAPACITY).toInt
    }
    if (element.hasAttribute(BOUND)) {
      properties.bound = element.getAttribute(BOUND).toInt
    }
    if (element.hasAttribute(FAIRNESS)) {
      properties.fairness = element.getAttribute(FAIRNESS).toBoolean
    }
    if (element.hasAttribute(CORE_POOL_SIZE)) {
      properties.corePoolSize = element.getAttribute(CORE_POOL_SIZE).toInt
    }
    if (element.hasAttribute(MAX_POOL_SIZE)) {
      properties.maxPoolSize = element.getAttribute(MAX_POOL_SIZE).toInt
    }
    if (element.hasAttribute(KEEP_ALIVE)) {
      properties.keepAlive = element.getAttribute(KEEP_ALIVE).toLong
    }
    if (element.hasAttribute(REJECTION_POLICY)) {
      properties.rejectionPolicy = element.getAttribute(REJECTION_POLICY)
    }
    properties
  }

  def hasRef(element: Element): Boolean = {
    val ref = element.getAttribute(REF)
    (ref != null) && !ref.isEmpty
  }

}
