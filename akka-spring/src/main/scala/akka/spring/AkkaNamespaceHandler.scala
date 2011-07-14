/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.beans.factory.xml.NamespaceHandlerSupport
import AkkaSpringConfigurationTags._

/**
 * Custom spring namespace handler for Akka.
 * @author michaelkober
 */
class AkkaNamespaceHandler extends NamespaceHandlerSupport {
  def init = {
    registerBeanDefinitionParser(CONFIG_TAG, new ConfigBeanDefinitionParser());
    registerBeanDefinitionParser(TYPED_ACTOR_TAG, new TypedActorBeanDefinitionParser())
    registerBeanDefinitionParser(UNTYPED_ACTOR_TAG, new UntypedActorBeanDefinitionParser())
    registerBeanDefinitionParser(SUPERVISION_TAG, new SupervisionBeanDefinitionParser())
    registerBeanDefinitionParser(DISPATCHER_TAG, new DispatcherBeanDefinitionParser())
    registerBeanDefinitionParser(CAMEL_SERVICE_TAG, new CamelServiceBeanDefinitionParser)
    registerBeanDefinitionParser(ACTOR_FOR_TAG, new ActorForBeanDefinitionParser());
  }
}
