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
import kernel.ActiveObjectFactory
import kernel.ActiveObjectProxy
import kernel.Supervisor
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
  private var bindings: List[DependencyBinding] = Nil
  private var configRegistry = new HashMap[Class[_], Component] // TODO is configRegistry needed?
  private var activeObjectRegistry = new HashMap[Class[_], Tuple3[Class[_], Class[_], ActiveObjectProxy]]
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
    log.debug("Creating new active object [%s]", clazz.getName)
    if (injector == null) throw new IllegalStateException("inject() and/or supervise() must be called before invoking getActiveObject(clazz)")
    val activeObjectOption: Option[Tuple3[Class[_], Class[_], ActiveObjectProxy]] = activeObjectRegistry.get(clazz)
    if (activeObjectOption.isDefined) {
      val classInfo = activeObjectOption.get
      val intfClass =         classInfo._1
      val implClass =         classInfo._2
      val activeObjectProxy = classInfo._3
      val target = implClass.newInstance
      injector.injectMembers(target)
      activeObjectProxy.setTargetInstance(target.asInstanceOf[AnyRef])
      activeObjectFactory.newInstance(intfClass, activeObjectProxy).asInstanceOf[T]
    } else throw new IllegalStateException("Class [" + clazz.getName + "] has not been put under supervision (by passing in the config to the 'supervise') method")
  }

  override def getActiveObjectProxy(clazz: Class[_]): ActiveObjectProxy = synchronized {
    log.debug("Looking up active object proxy [%s]", clazz.getName)
    if (injector == null) throw new IllegalStateException("inject() and/or supervise() must be called before invoking getActiveObjectProxy(clazz)")
    val activeObjectOption: Option[Tuple3[Class[_], Class[_], ActiveObjectProxy]] = activeObjectRegistry.get(clazz)
    if (activeObjectOption.isDefined) activeObjectOption.get._3
    else throw new IllegalStateException("Class [" + clazz.getName + "] has not been put under supervision (by passing in the config to the 'supervise') method")
  }

  override def getExternalDependency[T](clazz: Class[T]): T = synchronized {
    injector.getInstance(clazz).asInstanceOf[T]
  }

  override def getComponentInterfaces: List[Class[_]] = components.map(_.intf)
  
  override def getRoutingEndpoint(uri: String): Endpoint = synchronized {
    camelContext.getEndpoint(uri)
  }

  override def getRoutingEndpoints: java.util.Collection[Endpoint] = synchronized {
    camelContext.getEndpoints
  }

  override def getRoutingEndpoints(uri: String): java.util.Collection[Endpoint] = synchronized {
    camelContext.getEndpoints(uri)
  }

  override def configureActiveObjects(restartStrategy: RestartStrategy, components: List[Component]): ActiveObjectConfigurator = synchronized {
    this.restartStrategy = restartStrategy
    this.components = components.toArray.toList.asInstanceOf[List[Component]]
    bindings = for (c <- this.components)
               yield new DependencyBinding(c.intf, c.target) // build up the Guice interface class -> impl class bindings
    val deps = new java.util.ArrayList[DependencyBinding](bindings.size)
    for (b <- bindings) deps.add(b)
    modules.add(new ActiveObjectGuiceModule(deps))
    this
  }

  override def inject: ActiveObjectConfigurator = synchronized {
    if (injector != null) throw new IllegalStateException("inject() has already been called on this configurator")
    injector = Guice.createInjector(modules)
    this
  }

  override def supervise: ActiveObjectConfigurator = synchronized {
    if (injector == null) inject
    var workers = new java.util.ArrayList[Worker]
    for (component <- components) {
      val activeObjectProxy = new ActiveObjectProxy(component.intf, component.target, component.timeout)
      workers.add(Worker(activeObjectProxy.server, component.lifeCycle))
      activeObjectRegistry.put(component.intf, (component.intf, component.target, activeObjectProxy))
      camelContext.getRegistry.asInstanceOf[JndiRegistry].bind(component.name, activeObjectProxy)
      for (method <- component.intf.getDeclaredMethods.toList) {
        registerMethodForUri(method, component.name)
      }
      log.debug("Registering active object in Camel context under the name [%s]", component.target.getName)
    }
    supervisor = activeObjectFactory.supervise(restartStrategy, workers)
    camelContext.addComponent(AKKA_CAMEL_ROUTING_SCHEME, new ActiveObjectComponent(this))
    camelContext.start

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
    activeObjectRegistry = new HashMap[Class[_], Tuple3[Class[_], Class[_], ActiveObjectProxy]]
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
 