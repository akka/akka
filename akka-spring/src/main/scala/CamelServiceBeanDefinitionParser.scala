/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.xml.{ParserContext, AbstractSingleBeanDefinitionParser}
import org.springframework.util.xml.DomUtils
import org.w3c.dom.Element

import se.scalablesolutions.akka.spring.AkkaSpringConfigurationTags._


/**
 * @author Martin Krasser
 */
class CamelServiceBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    val camelContextElement = DomUtils.getChildElementByTagName(element, CAMEL_CONTEXT_TAG);
    if (camelContextElement ne null) {
      val camelContextReference = camelContextElement.getAttribute("ref")
      builder.addPropertyReference("camelContext", camelContextReference)
    }
  }
  
  override def getBeanClass(element: Element): Class[_] = classOf[CamelServiceFactoryBean]

  override def shouldGenerateIdAsFallback = true
}