/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.component

import java.util.Map
import java.util.concurrent.ConcurrentHashMap

import org.apache.camel.CamelContext
import org.apache.camel.component.bean._

import akka.actor._

/**
 * @author Martin Krasser
 */
object TypedActorComponent {
  /**
   * Default schema name for typed actor endpoint URIs.
   */
  val InternalSchema = "typed-actor-internal"
}

/**
 * Camel component for exchanging messages with typed actors. This component
 * tries to obtain the typed actor from <code>Actor.registry</code> if the
 * schema is <code>TypedActorComponent.InternalSchema</code>. If the schema
 * name is <code>typed-actor</code> this component tries to obtain the typed
 * actor from the CamelContext's registry.
 *
 * @see org.apache.camel.component.bean.BeanComponent
 *
 * @author Martin Krasser
 */
class TypedActorComponent extends BeanComponent {
  val typedActorRegistry = new ConcurrentHashMap[String, AnyRef]

  /**
   * Creates an <code>org.apache.camel.component.bean.BeanEndpoint</code> with a custom
   * bean holder that uses <code>Actor.registry</code> for getting access to typed actors
   * (beans).
   *
   * @see akka.camel.component.TypedActorHolder
   */
  override def createEndpoint(uri: String, remaining: String, parameters: Map[String, AnyRef]) = {
    val endpoint = new BeanEndpoint(uri, this)
    endpoint.setBeanName(remaining)
    endpoint.setBeanHolder(createBeanHolder(uri, remaining))
    setProperties(endpoint.getProcessor, parameters)
    endpoint
  }

  private def createBeanHolder(uri: String, beanName: String) =
    new TypedActorHolder(uri, getCamelContext, beanName).createCacheHolder
}

/**
 * <code>org.apache.camel.component.bean.BeanHolder</code> implementation that uses
 * <code>Actor.registry</code> for getting access to typed actors.
 *
 * @author Martin Krasser
 */
class TypedActorHolder(uri: String, context: CamelContext, name: String)
  extends RegistryBean(context, name) {

  /**
   * Returns an <code>akka.camel.component.BeanInfo</code> instance.
   */
  override def getBeanInfo: BeanInfo =
    new BeanInfo(getContext, getBean.getClass, getParameterMappingStrategy)

  /**
   * Obtains a typed actor from <code>Actor.registry</code> if the schema is
   * <code>TypedActorComponent.InternalSchema</code>. If the schema name is
   *  <code>typed-actor</code> this method obtains the typed actor from the
   * CamelContext's registry.
   *
   * @return a typed actor or <code>null</code>.
   */
  override def getBean: AnyRef = {
    val internal = uri.startsWith(TypedActorComponent.InternalSchema)
    if (internal) Actor.registry.local.typedActorFor(uuidFrom(getName)) getOrElse null else super.getBean
  }
}
