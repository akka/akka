/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.xml.NamespaceHandlerSupport
import AkkaSpringConfigurationTags._

/**
 * Custom spring namespace handler for Akka.
 * @author michaelkober
 */
class AkkaNamespaceHandler extends NamespaceHandlerSupport {
  def init = {
    registerBeanDefinitionParser(ACTIVE_OBJECT_TAG, new ActiveObjectBeanDefinitionParser());
    registerBeanDefinitionParser(SUPERVISION_TAG, new SupervisionBeanDefinitionParser());
    registerBeanDefinitionParser(DISPATCHER_TAG, new DispatcherBeanDefinitionParser());
  }
}