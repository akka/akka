/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import com.google.inject.*;
import com.google.inject.jsr250.ResourceProviderFactory;

import se.scalablesolutions.akka.kernel.configuration.*;
import se.scalablesolutions.akka.kernel.ActiveObjectFactory;
import se.scalablesolutions.akka.kernel.ActiveObjectProxy;
import se.scalablesolutions.akka.kernel.Supervisor;
import se.scalablesolutions.akka.kernel.Worker;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
public class ActiveObjectGuiceConfigurator {
  private List<Module> modules = new ArrayList<Module>();
  private Injector injector;
  private Supervisor supervisor;
  private RestartStrategy restartStrategy;
  private Component[] components;
  private Map<Class, Component> configRegistry = new HashMap<Class, Component>();
  private Map<Class, ActiveObjectProxy> activeObjectRegistry = new HashMap<Class, ActiveObjectProxy>();
  private ActiveObjectFactory activeObjectFactory = new ActiveObjectFactory();

  public synchronized <T> T getExternalDependency(Class<T> clazz) {
    return injector.getInstance(clazz);
  }

  /**
   * Returns the active abject that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  public synchronized <T> T getActiveObject(Class<T> clazz) {
    if (injector == null) throw new IllegalStateException("inject() and supervise() must be called before invoking newInstance(clazz)");
    if (activeObjectRegistry.containsKey(clazz)) {
      final ActiveObjectProxy activeObjectProxy = activeObjectRegistry.get(clazz);
      activeObjectProxy.setTargetInstance(injector.getInstance(clazz)); 
      return (T)activeObjectFactory.newInstance(clazz, activeObjectProxy);
    } else throw new IllegalStateException("Class " + clazz.getName() + " has not been put under supervision (by passing in the config to the supervise()  method");
  }

  public synchronized ActiveObjectGuiceConfigurator configureActiveObjects(final RestartStrategy restartStrategy, final Component[] components) {
    this.restartStrategy = restartStrategy;
    this.components = components;
    modules.add(new AbstractModule() {
      protected void configure() {
        bind(ResourceProviderFactory.class);
        for (int i = 0; i < components.length; i++) {
          Component c = components[i];
          bind((Class) c.intf()).to((Class) c.target()).in(Singleton.class);
        }
      }
    });
    return this;
  }

  public synchronized ActiveObjectGuiceConfigurator inject() {
    if (injector != null) throw new IllegalStateException("inject() has already been called on this configurator");
    injector = Guice.createInjector(modules);
    return this;
  }

  public synchronized ActiveObjectGuiceConfigurator supervise() {
    if (injector == null) inject();
    injector = Guice.createInjector(modules);
    List<Worker> workers = new ArrayList<Worker>();
    for (int i = 0; i < components.length; i++) {
      final Component c = components[i];
      final ActiveObjectProxy activeObjectProxy = new ActiveObjectProxy(c.intf(), c.target(), c.timeout());
      workers.add(c.newWorker(activeObjectProxy));
      activeObjectRegistry.put(c.intf(), activeObjectProxy);
    }
    supervisor = activeObjectFactory.supervise(restartStrategy.transform(), workers);
    return this;
  }


  /**
   * Add additional services to be wired in.
   * <pre>
   * ActiveObjectGuiceConfigurator.addExternalGuiceModule(new AbstractModule {
   *   protected void configure() {
   *     bind(Foo.class).to(FooImpl.class).in(Scopes.SINGLETON);
   *     bind(BarImpl.class);
   *     link(Bar.class).to(BarImpl.class);
   *     bindConstant(named("port")).to(8080);
   *   }})
   * </pre>
   */
  public synchronized ActiveObjectGuiceConfigurator addExternalGuiceModule(Module module) {
    modules.add(module);
    return this;
  }

  public List<Module> getGuiceModules() {
    return modules;
  }

  public synchronized void reset() {
    modules = new ArrayList<Module>();
    configRegistry = new HashMap<Class, Component>();
    activeObjectRegistry = new HashMap<Class, ActiveObjectProxy>();
    injector = null;
    restartStrategy = null;
  }

  public synchronized void stop() {
    // TODO: fix supervisor.stop();    
  }
}
