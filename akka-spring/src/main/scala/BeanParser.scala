/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import se.scalablesolutions.akka.util.Logging
import org.w3c.dom.Element
import org.springframework.util.xml.DomUtils

/**
 * Base trait with utility methods for bean parsing.
 */
trait BeanParser extends Logging {

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
