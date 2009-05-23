/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import com.google.inject._
import com.google.inject.jsr250.ResourceProviderFactory

import java.lang.reflect.Method
import javax.servlet.ServletContext

import org.apache.camel.impl.{JndiRegistry, DefaultCamelContext}
import org.apache.camel.{CamelContext, Endpoint, Routes}

import scala.collection.mutable.HashMap

import kernel.camel.ActiveObjectComponent
import kernel.{ActiveObjectFactory, Supervisor}
import kernel.config.ScalaConfig._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectGuiceConfigurator extends ActiveObjectConfigurator with CamelConfigurator with Logging {
  val AKKA_CAMEL_ROUTING_SCHEME = "akka"

  private var injector: Injector = _
  private var supervisor: Supervisor  = _
  private var restartStrategy: RestartStrategy  = _
  private var components: List[Component] = _
  private var workers: List[Worker] = Nil
  private var bindings: List[DependencyBinding] = Nil
  private var configRegistry = new HashMap[Class[_], Component] // TODO is configRegistry needed?
  private var activeObjectRegistry = new HashMap[Class[_], Tuple4[AnyRef, AnyRef, Component, GenericServerContainer]]
  private var activeObjectFactory = new ActiveObjectFactory
  private var camelContext = new DefaultCamelContext
  private var modules = new java.util.ArrayList[Module]
  private var methodToUriRegistry = new HashMap[Method, String]

  /**
   * Returns the active abject that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  override def getActiveObject[T](clazz: Class[T]): T = synchronized {
    log.debug("Retrieving active object [%s]", clazz.getName)
    if (injector == null) throw new IllegalStateException("inject() and/or supervise() must be called before invoking getActiveObject(clazz)")
    val (proxy, targetInstance, component, server) =
        activeObjectRegistry.getOrElse(clazz, throw new IllegalStateException("Class [" + clazz.getName + "] has not been put under supervision (by passing in the config to the 'configureActiveObjects' and then invoking 'supervise') method"))
    injector.injectMembers(targetInstance)
    proxy.asInstanceOf[T]
  }

 /*
  override def getActiveObjectProxy(clazz: Class[_]): ActiveObjectProxy = synchronized {
    log.debug("Looking up active object proxy [%s]", clazz.getName)
    if (injector == null) throw new IllegalStateException("inject() and/or supervise() must be called before invoking getActiveObjectProxy(clazz)")
    val activeObjectOption: Option[Tuple3[Class[_], Class[_], ActiveObjectProxy]] = activeObjectRegistry.get(clazz)
    if (activeObjectOption.isDefined) activeObjectOption.get._3
    else throw new IllegalStateException("Class [" + clazz.getName + "] has not been put under supervision (by passing in the config to the 'supervise') method")
  }
  */
  override def getExternalDependency[T](clazz: Class[T]): T = synchronized {
    injector.getInstance(clazz).asInstanceOf[T]
  }

  override def getComponentInterfaces: List[Class[_]] =
    for (c <- components) yield {
      if (c.intf.isDefined) c.intf.get
      else c.target
    }
 
  override def getRoutingEndpoint(uri: String): Endpoint = synchronized {
    camelContext.getEndpoint(uri)
  }

  override def getRoutingEndpoints: java.util.Collection[Endpoint] = synchronized {
    camelContext.getEndpoints
  }

  override def getRoutingEndpoints(uri: String): java.util.Collection[Endpoint] = synchronized {
    camelContext.getEndpoints(uri)
  }

  override def configureActiveObjects(
      restartStrategy: RestartStrategy,
      components: List[Component]): ActiveObjectConfigurator = synchronized {
    this.restartStrategy = restartStrategy
    this.components = components.toArray.toList.asInstanceOf[List[Component]]
    bindings = for (component <- this.components) yield {
      if (component.intf.isDefined) newDelegatingProxy(component)
      else                          newSubclassingProxy(component)
    }
    //camelContext.getRegistry.asInstanceOf[JndiRegistry].bind(component.name, activeObjectProxy)
    //for (method <- component.intf.getDeclaredMethods.toList) registerMethodForUri(method, component.name)
    //log.debug("Registering active object in Camel context under the name [%s]", component.target.getName)
    val deps = new java.util.ArrayList[DependencyBinding](bindings.size)
    for (b <- bindings) deps.add(b)
    modules.add(new ActiveObjectGuiceModule(deps))
    this
  }

  private def newSubclassingProxy(component: Component): DependencyBinding = {
    val targetClass = component.target
    val server = new GenericServerContainer(targetClass.getName, () => new Dispatcher(component.target.getName))
    server.setTimeout(component.timeout)
    workers ::= Worker(server, component.lifeCycle)
    val proxy = activeObjectFactory.newInstance(targetClass, server).asInstanceOf[AnyRef]
    activeObjectRegistry.put(targetClass, (proxy, proxy, component, server))
    new DependencyBinding(targetClass, proxy)
  }

  private def newDelegatingProxy(component: Component): DependencyBinding = {
    val targetClass = component.intf.get
    val server = new GenericServerContainer(targetClass.getName, () => new Dispatcher(component.target.getName))
    server.setTimeout(component.timeout)
    workers ::= Worker(server, component.lifeCycle)
    component.target.getConstructor(Array[Class[_]]()).setAccessible(true)
    val targetInstance = component.target.newInstance.asInstanceOf[AnyRef] // TODO: perhaps need to put in registry
    val proxy = activeObjectFactory.newInstance(targetClass, targetInstance, server).asInstanceOf[AnyRef]
    activeObjectRegistry.put(targetClass, (proxy, targetInstance, component, server))
    new DependencyBinding(targetClass, proxy)
  }

  override def inject: ActiveObjectConfigurator = synchronized {
    if (injector != null) throw new IllegalStateException("inject() has already been called on this configurator")
    injector = Guice.createInjector(modules)
    this
  }

  override def supervise: ActiveObjectConfigurator = synchronized {
    if (injector == null) inject
    supervisor = activeObjectFactory.supervise(restartStrategy, workers)
    //camelContext.addComponent(AKKA_CAMEL_ROUTING_SCHEME, new ActiveObjectComponent(this))
    //camelContext.start
    ActiveObjectConfigurator.registerConfigurator(this)
    this
  }

  /**
   * Add additional services to be wired in.
   * <pre>
   * activeObjectConfigurator.addExternalGuiceModule(new AbstractModule {
   *   protected void configure() {
   *     bind(Foo.class).to(FooImpl.class).in(Scopes.SINGLETON);
   *     bind(BarImpl.class);
   *     link(Bar.class).to(BarImpl.class);
   *     bindConstant(named("port")).to(8080);
   *   }})
   * </pre>
   */
  def addExternalGuiceModule(module: Module): ActiveObjectConfigurator  = synchronized {
    modules.add(module)
    this
  }

  override def addRoutes(routes: Routes): ActiveObjectConfigurator  = synchronized {
    camelContext.addRoutes(routes)
    this
  }

  override def getCamelContext: CamelContext = camelContext

  def getGuiceModules: java.util.List[Module] = modules

  def reset = synchronized {
    modules = new java.util.ArrayList[Module]
    configRegistry = new HashMap[Class[_], Component]
    activeObjectRegistry = new HashMap[Class[_], Tuple4[AnyRef, AnyRef, Component, GenericServerContainer]]
    methodToUriRegistry = new HashMap[Method, String]
    injector = null
    restartStrategy = null
    camelContext = new DefaultCamelContext
  }

  def stop = synchronized {
    camelContext.stop
    supervisor.stop
  }

  def registerMethodForUri(method: Method, componentName: String) =
    methodToUriRegistry += method -> buildUri(method, componentName)

  def lookupUriFor(method: Method): String =
    methodToUriRegistry.getOrElse(method, throw new IllegalStateException("Could not find URI for method [" + method.getName + "]"))

  def buildUri(method: Method, componentName: String): String =
    AKKA_CAMEL_ROUTING_SCHEME + ":" + componentName + "." + method.getName
}
 