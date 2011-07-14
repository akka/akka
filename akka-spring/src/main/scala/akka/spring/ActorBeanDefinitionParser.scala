/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser
import org.springframework.beans.factory.xml.ParserContext
import AkkaSpringConfigurationTags._
import org.w3c.dom.Element

/**
 * Parser for custom namespace configuration.
 * @author michaelkober
 */
class TypedActorBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActorParser {
  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    val typedActorConf = parseActor(element)
    typedActorConf.typed = TYPED_ACTOR_TAG
    typedActorConf.setAsProperties(builder)
  }

  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element): Class[_] = classOf[ActorFactoryBean]
}

/**
 * Parser for custom namespace configuration.
 * @author michaelkober
 */
class UntypedActorBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActorParser {
  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    val untypedActorConf = parseActor(element)
    untypedActorConf.typed = UNTYPED_ACTOR_TAG
    untypedActorConf.setAsProperties(builder)
  }

  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element): Class[_] = classOf[ActorFactoryBean]
}

/**
 * Parser for custom namespace configuration.
 * @author michaelkober
 */
class ActorForBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActorForParser {
  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    val actorForConf = parseActorFor(element)
    actorForConf.setAsProperties(builder)
  }

  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element): Class[_] = classOf[ActorForFactoryBean]
}

/**
 * Parser for custom namespace configuration.
 * @author michaelkober
 */
class ConfigBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActorParser {
  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    val location = element.getAttribute(LOCATION)
    builder.addPropertyValue(LOCATION, location)
  }

  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element): Class[_] = classOf[ConfiggyPropertyPlaceholderConfigurer]

  override def shouldGenerateId() = true
}
