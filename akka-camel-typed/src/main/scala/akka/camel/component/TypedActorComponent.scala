/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
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
   * Returns an <code>akka.camel.component.TypedActorInfo</code> instance.
   */
  override def getBeanInfo: BeanInfo =
    new TypedActorInfo(getContext, getBean.getClass, getParameterMappingStrategy)

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
    if (internal) Actor.registry.typedActorFor(uuidFrom(getName)) getOrElse null else super.getBean
  }
}

/**
 * Typed actor meta information.
 *
 * @author Martin Krasser
 */
class TypedActorInfo(context: CamelContext, clazz: Class[_], strategy: ParameterMappingStrategy)
  extends BeanInfo(context, clazz, strategy) {

  /**
   * Introspects AspectWerkz proxy classes.
   *
   * @param clazz AspectWerkz proxy class.
   */
  protected override def introspect(clazz: Class[_]): Unit = {

    // TODO: fix target class detection in BeanInfo.introspect(Class)
    // Camel assumes that classes containing a '$$' in the class name
    // are classes generated with CGLIB. This conflicts with proxies
    // created from interfaces with AspectWerkz. Once the fix is in
    // place this method can be removed.

    for (method ← clazz.getDeclaredMethods) {
      if (isValidMethod(clazz, method)) {
        introspect(clazz, method)
      }
    }
    val superclass = clazz.getSuperclass
    if ((superclass ne null) && !superclass.equals(classOf[AnyRef])) {
      introspect(superclass)
    }
  }
}
