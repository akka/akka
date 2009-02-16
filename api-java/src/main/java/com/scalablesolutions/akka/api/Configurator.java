/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.api;

import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.spi.CloseFailedException;

import java.util.List;
import java.util.ArrayList;

import javax.annotation.Resource;
import javax.naming.Context;

import scala.actors.behavior.*;

public class Configurator {

  static public Supervisor supervise(Configuration.RestartStrategy restartStrategy, List<Configuration.Component> components) {
    return null;
    // SupervisorFactory factory = new SupervisorFactory() {
    //   @Override public SupervisorConfig getSupervisorConfig() {
    //     new SupervisorConfig(restartStrategy, components.map(c => Worker(c.component.server, c.lifeCycle)))
    //   }
    // }
    // val supervisor = factory.newSupervisor
    // supervisor ! scala.actors.behavior.Start
    // supervisor
  }

  // private def supervise(proxy: ActiveObjectProxy): Supervisor =
  //   supervise(
  //     RestartStrategy(OneForOne, 5, 1000),
  //     Component(
  //       proxy,
  //       LifeCycle(Permanent, 100))
  //     :: Nil)

  // val fooProxy = new ActiveObjectProxy(new FooImpl, 1000)
  //   val barProxy = new ActiveObjectProxy(new BarImpl, 1000)

  //   val supervisor =
  //   ActiveObject.supervise(
  //                          RestartStrategy(AllForOne, 3, 100),
  //                          Component(
  //                                    fooProxy,
  //                                    LifeCycle(Permanent, 100)) ::
  //                          Component(
  //                                    barProxy,
  //                                    LifeCycle(Permanent, 100))
  //                          :: Nil)

  //   val foo = ActiveObject.newInstance[Foo](classOf[Foo], fooProxy)
  //   val bar = ActiveObject.newInstance[Bar](classOf[Bar], barProxy)



  //   public void testResourceInjection() throws CreationException, CloseFailedException {
  //   Injector injector = Guice.createInjector(new AbstractModule() {
  //       protected void configure() {
  //         bind(ResourceProviderFactory.class);
  //         bind(MyBean.class).in(Singleton.class);
  //       }

  //       @Provides
  //         public Context createJndiContext() throws Exception {
  //         Context answer = new JndiContext();
  //         answer.bind("foo", new AnotherBean("Foo"));
  //         answer.bind("xyz", new AnotherBean("XYZ"));
  //         return answer;
  //       }
  //     });

  //   MyBean bean = injector.getInstance(MyBean.class);
  //   assertNotNull("Should have instantiated the bean", bean);
  //   assertNotNull("Should have injected a foo", bean.foo);
  //   assertNotNull("Should have injected a bar", bean.bar);

  //   assertEquals("Should have injected correct foo", "Foo", bean.foo.name);
  //   assertEquals("Should have injected correct bar", "XYZ", bean.bar.name);
  // }

  //   public static class MyBean {
  //     @Resource
  //       public AnotherBean foo;

  //     public AnotherBean bar;

  //     @Resource(name = "xyz")
  //       public void bar(AnotherBean bar) {
  //       this.bar = bar;
  //     }
  //   }

  // static class AnotherBean {
  //   public String name = "undefined";

  //   AnotherBean(String name) {
  //     this.name = name;
  //   }

  // Injector injector = Guice.createInjector(new AbstractModule() {
  //   protected void configure() {
  //     Jsr250.bind(binder());

  //     bind(MyBean.class).in(Singleton.class);
  //   }
  // });

  // Injector injector = Guice.createInjector(new AbstractModule() {
  //   protected void configure() {
  //     bind(ResourceProviderFactory.class);
  //     bind(MyBean.class).in(Singleton.class);
  //   }

  //   @Provides
  //   public Context createJndiContext() throws Exception {
  //     Context answer = new JndiContext();
  //     answer.bind("foo", new AnotherBean("Foo"));
  //     answer.bind("xyz", new AnotherBean("XYZ"));
  //     return answer;
  //   }
  // });

  // MyBean bean = injector.getInstance(MyBean.class);
}

