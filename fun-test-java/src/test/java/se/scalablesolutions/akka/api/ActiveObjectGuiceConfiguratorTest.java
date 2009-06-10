/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.config.ActiveObjectGuiceConfiguratorForJava;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.config.*;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;
import se.scalablesolutions.akka.kernel.TransactionalMap;
import se.scalablesolutions.akka.kernel.InMemoryTransactionalMap;

import com.google.inject.Inject;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

import junit.framework.TestCase;

public class ActiveObjectGuiceConfiguratorTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectGuiceConfiguratorForJava conf = new ActiveObjectGuiceConfiguratorForJava();

  protected void setUp() {
    conf.addExternalGuiceModule(new AbstractModule() {
      protected void configure() {
        bind(Ext.class).to(ExtImpl.class).in(Scopes.SINGLETON);
      }
    }).configureActiveObjects(
        new RestartStrategy(new AllForOne(), 3, 5000), new Component[]{
            new Component(
                Foo.class,
                new LifeCycle(new Permanent(), 1000),
                10000),
            new Component(
                Bar.class,
                BarImpl.class,
                new LifeCycle(new Permanent(), 1000),
                10000)
        }).inject().supervise();

  }

  public void testGuiceActiveObjectInjection() {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    Bar bar = conf.getActiveObject(Bar.class);
    assertEquals(foo.getBar(), bar);
  }

  public void testGuiceExternalDependencyInjection() {
    messageLog = "";
    Bar bar = conf.getActiveObject(Bar.class);
    Ext ext = conf.getExternalDependency(Ext.class);
    assertTrue(bar.getExt().toString().equals(ext.toString()));
  }

  public void testLookupNonSupervisedInstance() {
    try {
      String str = conf.getActiveObject(String.class);
      fail("exception should have been thrown");
    } catch (Exception e) {
      assertEquals("Class [java.lang.String] has not been put under supervision (by passing in the config to the 'configureActiveObjects' and then invoking 'supervise') method", e.getMessage());
    }
  }

  public void testActiveObjectInvocation() throws InterruptedException {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    messageLog += foo.foo("foo ");
    foo.bar("bar ");
    messageLog += "before_bar ";
    Thread.sleep(500);
    assertEquals("foo return_foo before_bar ", messageLog);
  }

  public void testActiveObjectInvocationsInvocation() throws InterruptedException {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    Bar bar = conf.getActiveObject(Bar.class);
    messageLog += foo.foo("foo ");
    foo.bar("bar ");
    messageLog += "before_bar ";
    Thread.sleep(500);
    assertEquals("foo return_foo before_bar ", messageLog);
  }


  public void testForcedTimeout() {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    try {
      foo.longRunning();
      fail("exception should have been thrown");
    } catch (se.scalablesolutions.akka.kernel.reactor.FutureTimeoutException e) {
    }
  }

  public void testForcedException() {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    try {
      foo.throwsException();
      fail("exception should have been thrown");
    } catch (RuntimeException e) {
    }
  }
}


