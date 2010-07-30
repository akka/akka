/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.util.xml.DomUtils
import org.w3c.dom.Element
import scala.collection.JavaConversions._

import se.scalablesolutions.akka.actor.IllegalActorStateException

/**
 * Parser trait for custom namespace configuration for typed-actor.
 * @author michaelkober
 * @author <a href="johan.rask@jayway.com">Johan Rask</a>
 * @author Martin Krasser
 */
trait TypedActorParser extends BeanParser with DispatcherParser {
  import AkkaSpringConfigurationTags._

  /**
   * Parses the given element and returns a TypedActorProperties.
   * @param element dom element to parse
   * @return configuration for the typed actor
   */
  def parseTypedActor(element: Element): TypedActorProperties = {
    val objectProperties = new TypedActorProperties()
    val remoteElement = DomUtils.getChildElementByTagName(element, REMOTE_TAG);
    val restartCallbacksElement = DomUtils.getChildElementByTagName(element, RESTART_CALLBACKS_TAG);
    val shutdownCallbackElement = DomUtils.getChildElementByTagName(element, SHUTDOWN_CALLBACK_TAG);
    val dispatcherElement = DomUtils.getChildElementByTagName(element, DISPATCHER_TAG)
    val propertyEntries = DomUtils.getChildElementsByTagName(element,PROPERTYENTRY_TAG)

    if (remoteElement != null) {
      objectProperties.host = mandatory(remoteElement, HOST)
      objectProperties.port = mandatory(remoteElement, PORT).toInt
    }

    if (restartCallbacksElement != null) {
      objectProperties.preRestart = restartCallbacksElement.getAttribute(PRE_RESTART)
      objectProperties.postRestart = restartCallbacksElement.getAttribute(POST_RESTART)
      if ((objectProperties.preRestart.isEmpty) && (objectProperties.preRestart.isEmpty)) {
        throw new IllegalActorStateException("At least one of pre or post must be defined.")
      }
    }

    if (shutdownCallbackElement != null) {
      objectProperties.shutdown = shutdownCallbackElement.getAttribute("method")
    }

    if (dispatcherElement != null) {
      val dispatcherProperties = parseDispatcher(dispatcherElement)
      objectProperties.dispatcher = dispatcherProperties
    }

    for(element <- propertyEntries) {
            val entry = new PropertyEntry()
            entry.name = element.getAttribute("name");
        entry.value = element.getAttribute("value")
                entry.ref   = element.getAttribute("ref")
                objectProperties.propertyEntries.add(entry)
    }

    try {
      objectProperties.timeout = mandatory(element, TIMEOUT).toLong
    } catch {
      case nfe: NumberFormatException =>
        log.error(nfe, "could not parse timeout %s", element.getAttribute(TIMEOUT))
        throw nfe
    }

    objectProperties.target = mandatory(element, IMPLEMENTATION)
    objectProperties.transactional = if (element.getAttribute(TRANSACTIONAL).isEmpty) false else element.getAttribute(TRANSACTIONAL).toBoolean

    if (!element.getAttribute(INTERFACE).isEmpty) {
      objectProperties.interface = element.getAttribute(INTERFACE)
    }

    if (!element.getAttribute(LIFECYCLE).isEmpty) {
      objectProperties.lifecycle = element.getAttribute(LIFECYCLE)
    }

    if (!element.getAttribute(SCOPE).isEmpty) {
      objectProperties.scope = element.getAttribute(SCOPE)
    }

    objectProperties
  }

}
