/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.spring

import org.w3c.dom.Element
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.xml.{ ParserContext, AbstractSingleBeanDefinitionParser }

/**
 * Parser for custom namespace configuration.
 * @author michaelkober
 */
class DispatcherBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActorParser with DispatcherParser {
  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    val dispatcherProperties = parseDispatcher(element)
    dispatcherProperties.setAsProperties(builder)
  }

  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element): Class[_] = classOf[DispatcherFactoryBean]
}
