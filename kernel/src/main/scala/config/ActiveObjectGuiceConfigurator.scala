/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import com.google.inject._
import com.google.inject.jsr250.ResourceProviderFactory

import java.lang.reflect.Method
import kernel.camel.ActiveObjectComponent
import org.apache.camel.impl.{JndiRegistry, DefaultCamelContext}
import org.apache.camel.{CamelContext, Endpoint, Routes}
import scala.collection.mutable.HashMap
import se.scalablesolutions.akka.kernel.ActiveObjectFactory
import se.scalablesolutions.akka.kernel.ActiveObjectProxy
import se.scalablesolutions.akka.kernel.Supervisor
import se.scalablesolutions.akka.kernel.config.ScalaConfig._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectGuiceConfigurator extends Logging {
  val AKKA_CAMEL_ROUTING_SCHEME = "akka"

  private var injector: Injector = _
  private var supervisor: Supervisor  = _
  private var restartStrategy: RestartStrategy  = _
  private var components: List[Component] = _
  private var bindings: List[DependencyBinding] = Nil
  private var configRegistry = new HashMap[Class[_], Component] // TODO is configRegistry needed?
  private var activeObjectRegistry = new HashMap[String, Tuple3[Class[_], Class[_], ActiveObjectProxy]]
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
  def getActiveObject(name: String): AnyRef = synchronized {
  //def getActiveObject[T](name: String): T = synchronized {
    log.debug("Looking up active object [%s]", name)
    if (injector == null) throw new IllegalStateException("inject() and/or supervise() must be called before invoking newInstance(clazz)")
    val activeObjectOption: Option[Tuple3[Class[_], Class[_], ActiveObjectProxy]] = activeObjectRegistry.get(name)
    if (activeObjectOption.isDefined) {
      val classInfo = activeObjectOption.get
      val intfClass = classInfo._1
      val implClass = classInfo._2
      val activeObjectProxy = classInfo._3
      val target = implClass.newInstance
      injector.injectMembers(target)
      activeObjectProxy.setTargetInstance(target.asInstanceOf[AnyRef])
      activeObjectFactory.newInstance(intfClass, activeObjectProxy).asInstanceOf[AnyRef]
    } else throw new IllegalStateException("Class " + name + " has not been put under supervision (by passing in the config to the 'supervise') method")
  }

  def getActiveObjectProxy(name: String): ActiveObjectProxy = synchronized {
    log.debug("Looking up active object proxy [%s]", name)
    if (injector == null) throw new IllegalStateException("inject() and supervise() must be called before invoking newInstance(clazz)")
    val activeObjectOption: Option[Tuple3[Class[_], Class[_], ActiveObjectProxy]] = activeObjectRegistry.get(name)
    if (activeObjectOption.isDefined) activeObjectOption.get._3
    else throw new IllegalStateException("Class " + name + " has not been put under supervision (by passing in the config to the 'supervise') method")
  }

  def getExternalDependency[T](clazz: Class[T]): T = synchronized {
    injector.getInstance(clazz).asInstanceOf[T]
  }

  def getRoutingEndpoint(uri: String): Endpoint = synchronized {
    camelContext.getEndpoint(uri)
  }

  def getRoutingEndpoints: java.util.Collection[Endpoint] = synchronized {
    camelContext.getEndpoints
  }

  def getRoutingEndpoints(uri: String): java.util.Collection[Endpoint] = synchronized {
    camelContext.getEndpoints(uri)
  }

  def configureActiveObjects(restartStrategy: RestartStrategy, components: List[Component]): ActiveObjectGuiceConfigurator = synchronized {
    this.restartStrategy = restartStrategy
    this.components = components.toArray.toList.asInstanceOf[List[Component]]
    bindings = for (c <- this.components)
               yield new DependencyBinding(c.intf, c.target) // build up the Guice interface class -> impl class bindings
    val arrayList = new java.util.ArrayList[DependencyBinding]()
    for (b <- bindings) arrayList.add(b)
    modules.add(new ActiveObjectGuiceModule(arrayList))
    this
  }

  def inject: ActiveObjectGuiceConfigurator = synchronized {
    if (injector != null) throw new IllegalStateException("inject() has already been called on this configurator")
    injector = Guice.createInjector(modules)
    this
  }

  def supervise: ActiveObjectGuiceConfigurator = synchronized {
    if (injector == null) inject
    var workers = new java.util.ArrayList[Worker]
    for (component <- components) {
      val activeObjectProxy = new ActiveObjectProxy(component.intf, component.target, component.timeout)
      workers.add(Worker(activeObjectProxy.server, component.lifeCycle))
      activeObjectRegistry.put(component.name, (component.intf, component.target, activeObjectProxy))
      camelContext.getRegistry.asInstanceOf[JndiRegistry].bind(component.name, activeObjectProxy)
      for (method <- component.intf.getDeclaredMethods.toList) {
        registerMethodForUri(method, component.name)
      }
      log.debug("Registering active object in Camel context under the name [%s]", component.target.getName)
    }
    supervisor = activeObjectFactory.supervise(restartStrategy, workers)
    camelContext.addComponent(AKKA_CAMEL_ROUTING_SCHEME, new ActiveObjectComponent(this))
    camelContext.start
    this
  }

  /**
   * Add additional services to be wired in.
   * <pre>
   * ActiveObjectGuiceModule.addExternalGuiceModule(new AbstractModule {
   *   protected void configure() {
   *     bind(Foo.class).to(FooImpl.class).in(Scopes.SINGLETON);
   *     bind(BarImpl.class);
   *     link(Bar.class).to(BarImpl.class);
   *     bindConstant(named("port")).to(8080);
   *   }})
   * </pre>
   */
  def addExternalGuiceModule(module: Module): ActiveObjectGuiceConfigurator  = synchronized {
    modules.add(module)
    this
  }

  /**
   * Add Camel routes for the active objects.
   * <pre>
   * activeObjectGuiceModule.addRoutes(new RouteBuilder() {
   *   def configure = {
   *     from("akka:actor1").to("akka:actor2")
   *     from("akka:actor2").process(new Processor() {
   *       def process(e: Exchange) = {
   *         println("Received exchange: " + e.getIn())
   *       }
   *     })
   *   }
   * }).inject().supervise();
   * </pre>
   */
  def addRoutes(routes: Routes): ActiveObjectGuiceConfigurator  = synchronized {
    camelContext.addRoutes(routes)
    this
  }

  def getCamelContext: CamelContext = camelContext

  def getGuiceModules: java.util.List[Module] = modules

  def reset = synchronized {
    modules = new java.util.ArrayList[Module]
    configRegistry = new HashMap[Class[_], Component]
    activeObjectRegistry = new HashMap[String, Tuple3[Class[_], Class[_], ActiveObjectProxy]]
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
 