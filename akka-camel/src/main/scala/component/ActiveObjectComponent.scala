/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component

import java.util.Map
import java.util.concurrent.ConcurrentHashMap
import org.apache.camel.CamelContext
import org.apache.camel.component.bean._

/**
 * @author Martin Krasser
 */
object ActiveObjectComponent {
  /**
   * Default schema name for active object endpoint URIs.
   */
  val InternalSchema = "active-object-internal"
}

/**
 * Camel component for exchanging messages with active objects. This component
 * tries to obtain the active object from the <code>activeObjectRegistry</code>
 * first. If it's not there it tries to obtain it from the CamelContext's registry.
 *
 * @see org.apache.camel.component.bean.BeanComponent
 *
 * @author Martin Krasser
 */
class ActiveObjectComponent extends BeanComponent {
  val activeObjectRegistry = new ConcurrentHashMap[String, AnyRef]

  /**
   * Creates a {@link org.apache.camel.component.bean.BeanEndpoint} with a custom
   * bean holder that uses <code>activeObjectRegistry</code> for getting access to
   * active objects (beans).
   *
   * @see se.scalablesolutions.akka.camel.component.ActiveObjectHolder
   */
  override def createEndpoint(uri: String, remaining: String, parameters: Map[String, AnyRef]) = {
    val endpoint = new BeanEndpoint(uri, this)
    endpoint.setBeanName(remaining)
    endpoint.setBeanHolder(createBeanHolder(remaining))
    setProperties(endpoint.getProcessor, parameters)
    endpoint
  }

  private def createBeanHolder(beanName: String) =
    new ActiveObjectHolder(activeObjectRegistry, getCamelContext, beanName).createCacheHolder
}

/**
 * {@link org.apache.camel.component.bean.BeanHolder} implementation that uses a custom
 * registry for getting access to active objects.
 *
 * @author Martin Krasser
 */
class ActiveObjectHolder(activeObjectRegistry: Map[String, AnyRef], context: CamelContext, name: String)
    extends RegistryBean(context, name) {

  /**
   * Returns an {@link se.scalablesolutions.akka.camel.component.ActiveObjectInfo} instance.
   */
  override def getBeanInfo: BeanInfo =
    new ActiveObjectInfo(getContext, getBean.getClass, getParameterMappingStrategy)

  /**
   * Obtains an active object from <code>activeObjectRegistry</code>.
   */
  override def getBean: AnyRef = {
    val bean = activeObjectRegistry.get(getName)
    if (bean eq null) super.getBean else bean
  }
}

/**
 * Provides active object meta information.
 *
 * @author Martin Krasser
 */
class ActiveObjectInfo(context: CamelContext, clazz: Class[_], strategy: ParameterMappingStrategy)
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

    for (method <- clazz.getDeclaredMethods) {
      if (isValidMethod(clazz, method)) {
        introspect(clazz, method)
      }
    }
    val superclass = clazz.getSuperclass
    if (superclass != null && !superclass.equals(classOf[AnyRef])) {
      introspect(superclass)
    }
  }
}
