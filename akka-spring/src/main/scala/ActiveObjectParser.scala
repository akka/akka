/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.util.xml.DomUtils
import org.w3c.dom.Element

/**
 * Parser trait for custom namespace configuration for active-object.
 * @author michaelkober
 */
trait ActiveObjectParser extends BeanParser with DispatcherParser {
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
    val dispatcherElement = DomUtils.getChildElementByTagName(element, DISPATCHER_TAG)

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

    if (dispatcherElement != null) {
      val dispatcherProperties = parseDispatcher(dispatcherElement)
      objectProperties.dispatcher = dispatcherProperties
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

}