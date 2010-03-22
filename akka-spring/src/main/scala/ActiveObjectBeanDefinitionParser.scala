/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.util.xml.DomUtils
import se.scalablesolutions.akka.util.Logging
import org.w3c.dom.Element

/**
 * Parser for custom namespace configuration for active-object.
 * @author michaelkober
 */
trait ActiveObjectBeanDefinitionParser extends Logging {
  import AkkaSpringConfigurationTags._

  /**
   * Parses the given element and returns a ActiveObjectProperties.
   * @param element dom element to parse
   * @return configuration for the active object 
   */
  def parseActiveObject(element: Element): ActiveObjectProperties = {
    val objectProperties = new ActiveObjectProperties()
    val remoteElement = DomUtils.getChildElementByTagName(element, REMOTE_TAG);
    val callbacksElement = DomUtils.getChildElementByTagName(element, RESTART_CALLBACKS_TAG);

    if (remoteElement != null) {
      objectProperties.host = mandatory(remoteElement, HOST)
      objectProperties.port = mandatory(remoteElement, PORT).toInt
    }

    if (callbacksElement != null) {
      objectProperties.preRestart = callbacksElement.getAttribute(PRE_RESTART)
      objectProperties.postRestart = callbacksElement.getAttribute(POST_RESTART)
      if ((objectProperties.preRestart.isEmpty) && (objectProperties.preRestart.isEmpty)) {
        throw new IllegalStateException("At least one of pre or post must be defined.")
      }
    }

    try {
      objectProperties.timeout = mandatory(element, TIMEOUT).toLong
    } catch {
      case nfe: NumberFormatException =>
        log.error(nfe, "could not parse timeout %s", element.getAttribute(TIMEOUT))
        throw nfe
    }

    objectProperties.target = mandatory(element, TARGET)
    objectProperties.transactional = if (element.getAttribute(TRANSACTIONAL).isEmpty) false else element.getAttribute(TRANSACTIONAL).toBoolean

    if (!element.getAttribute(INTERFACE).isEmpty) {
      objectProperties.interface = element.getAttribute(INTERFACE)
    }

    if (!element.getAttribute(LIFECYCLE).isEmpty) {
      objectProperties.lifecyclye = element.getAttribute(LIFECYCLE)
    }
    objectProperties
  }

  /**
   * Get a mandatory element attribute.
   * @param element the element with the mandatory attribute
   * @param attribute name of the mandatory attribute
   */
  def mandatory(element: Element, attribute: String): String = {
    if ((element.getAttribute(attribute) == null) || (element.getAttribute(attribute).isEmpty)) {
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
    if (childElement == null) {
      throw new IllegalArgumentException("Mandatory element missing: '<akka:" + childName + ">'")
    } else {
      childElement
    }
  }

}