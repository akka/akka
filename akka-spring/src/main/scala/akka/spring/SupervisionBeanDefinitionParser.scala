/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.xml.{ ParserContext, AbstractSingleBeanDefinitionParser }
import AkkaSpringConfigurationTags._

import org.w3c.dom.Element
import org.springframework.util.xml.DomUtils

/**
 * Parser for custom namespace for Akka declarative supervisor configuration.
 * @author michaelkober
 */
class SupervisionBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActorParser {
  /* (non-Javadoc)
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    parseSupervisor(element, builder)
  }

  /**
   * made accessible for testing
   */
  private[akka] def parseSupervisor(element: Element, builder: BeanDefinitionBuilder) {
    val strategyElement = mandatoryElement(element, STRATEGY_TAG)
    val typedActorsElement = DomUtils.getChildElementByTagName(element, TYPED_ACTORS_TAG)
    val untypedActorsElement = DomUtils.getChildElementByTagName(element, UNTYPED_ACTORS_TAG)
    if ((typedActorsElement eq null) && (untypedActorsElement eq null)) {
      throw new IllegalArgumentException("One of 'akka:typed-actors' or 'akka:untyped-actors' needed.")
    }
    parseRestartStrategy(strategyElement, builder)
    if (typedActorsElement ne null) {
      builder.addPropertyValue("typed", AkkaSpringConfigurationTags.TYPED_ACTOR_TAG)
      parseTypedActorList(typedActorsElement, builder)
    } else {
      builder.addPropertyValue("typed", AkkaSpringConfigurationTags.UNTYPED_ACTOR_TAG)
      parseUntypedActorList(untypedActorsElement, builder)
    }
  }

  private[akka] def parseRestartStrategy(element: Element, builder: BeanDefinitionBuilder) {
    val failover = mandatory(element, FAILOVER)
    val timeRange = mandatory(element, TIME_RANGE).toInt
    val retries = mandatory(element, RETRIES).toInt
    val trapExitsElement = mandatoryElement(element, TRAP_EXISTS_TAG)
    val trapExceptions = parseTrapExits(trapExitsElement)

    val restartStrategy = failover match {
      case "AllForOne" ⇒ new AllForOneStrategy(trapExceptions, retries, timeRange)
      case "OneForOne" ⇒ new OneForOneStrategy(trapExceptions, retries, timeRange)
      case _           ⇒ new OneForOneStrategy(trapExceptions, retries, timeRange) //Default to OneForOne
    }
    builder.addPropertyValue("restartStrategy", restartStrategy)
  }

  private[akka] def parseTypedActorList(element: Element, builder: BeanDefinitionBuilder) {
    val typedActors = DomUtils.getChildElementsByTagName(element, TYPED_ACTOR_TAG).toArray.toList.asInstanceOf[List[Element]]
    val actorProperties = typedActors.map(parseActor(_))
    builder.addPropertyValue("supervised", actorProperties)
  }

  private[akka] def parseUntypedActorList(element: Element, builder: BeanDefinitionBuilder) {
    val untypedActors = DomUtils.getChildElementsByTagName(element, UNTYPED_ACTOR_TAG).toArray.toList.asInstanceOf[List[Element]]
    val actorProperties = untypedActors.map(parseActor(_))
    builder.addPropertyValue("supervised", actorProperties)
  }

  private def parseTrapExits(element: Element): Array[Class[_ <: Throwable]] = {
    val trapExits = DomUtils.getChildElementsByTagName(element, TRAP_EXIT_TAG).toArray.toList.asInstanceOf[List[Element]]
    trapExits.map(DomUtils.getTextValue(_).toClass.asInstanceOf[Class[_ <: Throwable]]).toArray
  }

  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element): Class[_] = classOf[SupervisionFactoryBean]
}
