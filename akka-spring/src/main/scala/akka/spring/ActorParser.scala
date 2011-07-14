/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.util.xml.DomUtils
import org.w3c.dom.Element
import scala.collection.JavaConversions._

/**
 * Parser trait for custom namespace configuration for typed-actor.
 * @author michaelkober
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 * @author Martin Krasser
 */
trait ActorParser extends BeanParser with DispatcherParser {
  import AkkaSpringConfigurationTags._

  /**
   * Parses the given element and returns a TypedActorProperties.
   * @param element dom element to parse
   * @return configuration for the typed actor
   */
  def parseActor(element: Element): ActorProperties = {
    val objectProperties = new ActorProperties()
    val remoteElement = DomUtils.getChildElementByTagName(element, REMOTE_TAG);
    val dispatcherElement = DomUtils.getChildElementByTagName(element, DISPATCHER_TAG)
    val propertyEntries = DomUtils.getChildElementsByTagName(element, PROPERTYENTRY_TAG)

    if (remoteElement ne null) {
      objectProperties.host = mandatory(remoteElement, HOST)
      objectProperties.port = mandatory(remoteElement, PORT)
      objectProperties.serverManaged = SERVER_MANAGED == remoteElement.getAttribute(MANAGED_BY)
      val serviceName = remoteElement.getAttribute(SERVICE_NAME)
      if ((serviceName ne null) && (!serviceName.isEmpty)) {
        objectProperties.serviceName = serviceName
        objectProperties.serverManaged = true
      }
    }

    if (dispatcherElement ne null) {
      val dispatcherProperties = parseDispatcher(dispatcherElement)
      objectProperties.dispatcher = dispatcherProperties
    }

    for (element ← propertyEntries) {
      val entry = new PropertyEntry
      entry.name = element.getAttribute("name");
      entry.value = element.getAttribute("value")
      entry.ref = element.getAttribute("ref")
      objectProperties.propertyEntries.add(entry)
    }

    objectProperties.timeoutStr = element.getAttribute(TIMEOUT)
    objectProperties.target = if (element.getAttribute(IMPLEMENTATION).isEmpty) null else element.getAttribute(IMPLEMENTATION)
    objectProperties.beanRef = if (element.getAttribute(BEANREF).isEmpty) null else element.getAttribute(BEANREF)
    objectProperties.id = element.getAttribute("id")
    objectProperties.autostart = element.getAttribute(AUTOSTART) match {
      case null | "" ⇒ false
      case other     ⇒ other.toBoolean
    }
    objectProperties.dependsOn = element.getAttribute(DEPENDS_ON) match {
      case null | "" ⇒ Array[String]()
      case other     ⇒ for (dep ← other.split(",")) yield dep.trim
    }

    if (objectProperties.target == null && objectProperties.beanRef == null) {
      throw new IllegalArgumentException("Mandatory attribute missing, you need to provide either implementation or ref  ")
    }

    if (element.hasAttribute(INTERFACE)) {
      objectProperties.interface = element.getAttribute(INTERFACE)
    }
    if (element.hasAttribute(LIFECYCLE)) {
      objectProperties.lifecycle = element.getAttribute(LIFECYCLE)
    }
    if (element.hasAttribute(SCOPE)) {
      objectProperties.scope = element.getAttribute(SCOPE)
    }

    objectProperties
  }

}

/**
 * Parser trait for custom namespace configuration for RemoteClient actor-for.
 * @author michaelkober
 */
trait ActorForParser extends BeanParser {
  import AkkaSpringConfigurationTags._

  /**
   * Parses the given element and returns a ActorForProperties.
   * @param element dom element to parse
   * @return configuration for the typed actor
   */
  def parseActorFor(element: Element): ActorForProperties = {
    val objectProperties = new ActorForProperties()

    objectProperties.host = mandatory(element, HOST)
    objectProperties.port = mandatory(element, PORT)
    objectProperties.serviceName = mandatory(element, SERVICE_NAME)
    if (element.hasAttribute(INTERFACE)) {
      objectProperties.interface = element.getAttribute(INTERFACE)
    }
    objectProperties
  }

}

/**
 * Base trait with utility methods for bean parsing.
 */
trait BeanParser {

  /**
   * Get a mandatory element attribute.
   * @param element the element with the mandatory attribute
   * @param attribute name of the mandatory attribute
   */
  def mandatory(element: Element, attribute: String): String = {
    if ((element.getAttribute(attribute) eq null) || (element.getAttribute(attribute).isEmpty)) {
      throw new IllegalArgumentException("Mandatory attribute missing: " + attribute)
    } else {
      element.getAttribute(attribute)
    }
  }

  /**
   * Get a mandatory child element.
   * @param element the parent element
   * @param childName name of the mandatory child element
   */
  def mandatoryElement(element: Element, childName: String): Element = {
    val childElement = DomUtils.getChildElementByTagName(element, childName);
    if (childElement eq null) {
      throw new IllegalArgumentException("Mandatory element missing: '<akka:" + childName + ">'")
    } else {
      childElement
    }
  }

}

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
      if (dispatcherElement eq null) {
        throw new IllegalArgumentException("Referenced dispatcher not found: '" + ref + "'")
      }
    }

    properties.dispatcherType = mandatory(dispatcherElement, TYPE)
    if (properties.dispatcherType == THREAD_BASED) {
      val allowedParentNodes = "akka:typed-actor" :: "akka:untyped-actor" :: "typed-actor" :: "untyped-actor" :: Nil
      if (!allowedParentNodes.contains(dispatcherElement.getParentNode.getNodeName)) {
        throw new IllegalArgumentException("Thread based dispatcher must be nested in 'typed-actor' or 'untyped-actor' element!")
      }
    }

    properties.name = mandatory(dispatcherElement, NAME)

    val threadPoolElement = DomUtils.getChildElementByTagName(dispatcherElement, THREAD_POOL_TAG);
    if (threadPoolElement ne null) {
      if (properties.dispatcherType == THREAD_BASED) {
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
    if (element.hasAttribute(MAILBOX_CAPACITY)) {
      properties.mailboxCapacity = element.getAttribute(MAILBOX_CAPACITY).toInt
    }
    properties
  }

  def hasRef(element: Element): Boolean = {
    val ref = element.getAttribute(REF)
    (ref ne null) && !ref.isEmpty
  }

}

