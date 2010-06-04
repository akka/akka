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
  val DefaultSchema = "actobj"
}

/**
 * @author Martin Krasser
 */
class ActiveObjectComponent extends BeanComponent {
  val activeObjectRegistry = new ConcurrentHashMap[String, AnyRef]

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
 * @author Martin Krasser
 */
class ActiveObjectHolder(activeObjectRegistry: Map[String, AnyRef], context: CamelContext, name: String)
    extends RegistryBean(context, name) {

  override def getBeanInfo: BeanInfo =
    new ActiveObjectInfo(getContext, getBean.getClass, getParameterMappingStrategy)

  override def getBean: AnyRef =
    activeObjectRegistry.get(getName)
}

/**
 * @author Martin Krasser
 */
class ActiveObjectInfo(context: CamelContext, clazz: Class[_], strategy: ParameterMappingStrategy)
    extends BeanInfo(context, clazz, strategy) {

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
    if (superclass != null && !superclass.equals(classOf[Any])) {
      introspect(superclass)
    }
  }
}