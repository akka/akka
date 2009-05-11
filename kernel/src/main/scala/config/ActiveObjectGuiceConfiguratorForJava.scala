/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import akka.kernel.config.JavaConfig._
import akka.kernel.{Supervisor, ActiveObjectProxy, ActiveObjectFactory}

import com.google.inject._
import com.google.inject.jsr250.ResourceProviderFactory

import java.util.{ArrayList, HashMap, Collection}
import org.apache.camel.impl.{JndiRegistry, DefaultCamelContext}
import org.apache.camel.{Endpoint, Routes}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectGuiceConfiguratorForJava {
  private var modules = new ArrayList[Module]
  private var injector: Injector = _
  private var supervisor: Supervisor  = _
  private var restartStrategy: RestartStrategy  = _
  private var components: List[Component] = _
  private var bindings: List[DependencyBinding] = Nil
  private var configRegistry = new HashMap[Class[_], Component] // TODO is configRegistry needed?
  private var activeObjectRegistry = new HashMap[String, Tuple2[Class[_], ActiveObjectProxy]]
  private var activeObjectFactory = new ActiveObjectFactory
  //private var camelContext = new DefaultCamelContext();

  def getExternalDependency[T](clazz: Class[T]): T = synchronized {
    injector.getInstance(clazz).asInstanceOf[T]
  }
/*
  def getRoutingEndpoint(uri: String): Endpoint = synchronized {
    camelContext.getEndpoint(uri)
  }

  def getRoutingEndpoints: Collection[Endpoint] = synchronized {
    camelContext.getEndpoints
  }

  def getRoutingEndpoints(uri: String): Collection[Endpoint] = synchronized {
    camelContext.getEndpoints(uri)
  }
*/
  /**
   * Returns the active abject that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  def getActiveObject[T](name: String): T = synchronized {
    if (injector == null) throw new IllegalStateException("inject() and supervise() must be called before invoking newInstance(clazz)")
    if (activeObjectRegistry.containsKey(name)) {
      val activeObjectTuple = activeObjectRegistry.get(name)
      val clazz = activeObjectTuple._1
      val activeObjectProxy = activeObjectTuple._2
      activeObjectProxy.setTargetInstance(injector.getInstance(clazz).asInstanceOf[AnyRef])
      //val target = clazz.newInstance
      //injector.injectMembers(target)
      //activeObjectProxy.setTargetInstance(target.asInstanceOf[AnyRef])
      activeObjectFactory.newInstance(clazz, activeObjectProxy).asInstanceOf[T]
    } else throw new IllegalStateException("Class " + name + " has not been put under supervision (by passing in the config to the supervise()  method")
  }

  def configureActiveObjects(restartStrategy: RestartStrategy, components: Array[Component]): ActiveObjectGuiceConfiguratorForJava = synchronized {
    this.restartStrategy = restartStrategy
    this.components = components.toArray.toList.asInstanceOf[List[Component]]
    bindings = for (c <- this.components) yield {
      new DependencyBinding(c.intf, c.target)
    }
    val arrayList = new ArrayList[DependencyBinding]()
    for (b <- bindings) arrayList.add(b)
    modules.add(new ActiveObjectGuiceModule(arrayList))
    this
  }

  def inject(): ActiveObjectGuiceConfiguratorForJava = synchronized {
    if (injector != null) throw new IllegalStateException("inject() has already been called on this configurator")
    injector = Guice.createInjector(modules)
    this
  }

  def supervise: ActiveObjectGuiceConfiguratorForJava = synchronized {
    if (injector == null) inject()
    injector = Guice.createInjector(modules)
    val workers = new java.util.ArrayList[se.scalablesolutions.akka.kernel.config.ScalaConfig.Worker]
    for (c <- components) {
      val activeObjectProxy = new ActiveObjectProxy(c.intf, c.target, c.timeout)
      workers.add(c.newWorker(activeObjectProxy))
      activeObjectRegistry.put(c.name, (c.intf, activeObjectProxy))
//      camelContext.getRegistry.asInstanceOf[JndiRegistry].bind(c.intf.getName, activeObjectProxy)
    }
    supervisor = activeObjectFactory.supervise(restartStrategy.transform, workers)
//    camelContext.start
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
  def addExternalGuiceModule(module: Module): ActiveObjectGuiceConfiguratorForJava = synchronized {
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
   *
  def addRoutes(routes: Routes): ActiveObjectGuiceConfiguratorForJava  = synchronized {
    camelContext.addRoutes(routes)
    this
  }
  */

  def getGuiceModules = modules

  def reset = synchronized {
    modules = new ArrayList[Module]
    configRegistry = new HashMap[Class[_], Component]
    activeObjectRegistry = new HashMap[String, Tuple2[Class[_], ActiveObjectProxy]]
    injector = null
    restartStrategy = null
    //camelContext = new DefaultCamelContext
  }

  def stop = synchronized {
    //camelContext.stop
    supervisor.stop
  }
}
