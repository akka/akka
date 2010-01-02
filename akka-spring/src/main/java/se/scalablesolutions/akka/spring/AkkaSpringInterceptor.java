/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring;

import se.scalablesolutions.akka.actor.ActiveObjectAspect;
import se.scalablesolutions.akka.actor.AspectInit;
import se.scalablesolutions.akka.actor.AspectInitRegistry;
import se.scalablesolutions.akka.actor.Dispatcher;

import org.springframework.beans.factory.config.BeanPostProcessor;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Method;

public class AkkaSpringInterceptor extends ActiveObjectAspect implements MethodInterceptor, BeanPostProcessor {

  static final String TRANSACTION_MANAGER_CLASS_NAME = "org.springframework.transaction.support.TransactionSynchronizationManager";
  static final String IS_TRANSACTION_ALIVE_METHOD_NAME = "isActualTransactionActive";

  static private Method IS_TRANSACTION_ALIVE_METHOD = null;

  static {
    try {
      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      Class clazz = null;
      if (contextClassLoader != null) {
        clazz = contextClassLoader.loadClass(TRANSACTION_MANAGER_CLASS_NAME);
      } else {
        ClassLoader springClassLoader = AkkaSpringInterceptor.class.getClassLoader();
        clazz = springClassLoader.loadClass(TRANSACTION_MANAGER_CLASS_NAME);
      }
      if (clazz != null) IS_TRANSACTION_ALIVE_METHOD = clazz.getDeclaredMethod(IS_TRANSACTION_ALIVE_METHOD_NAME, null);
    } catch (Exception e) {
    }
  }

  // FIXME make configurable
  static final int TIME_OUT = 1000;

  @Override
  public Object invoke(MethodInvocation methodInvocation) throws Throwable {
    Dispatcher dispatcher = new Dispatcher(isTransactional());
    dispatcher.start();
    AspectInitRegistry.register(methodInvocation.getThis(), new AspectInit(
        methodInvocation.getThis().getClass(),
        dispatcher,
        TIME_OUT));
    Object result = this.invoke(AkkaSpringJoinPointWrapper.createSpringAkkaAspectWerkzWrapper(methodInvocation));
    dispatcher.stop();
    return result;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String arg) throws BeansException {
    return bean;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String arg) throws BeansException {
    return bean;
  }

  /**
   * Checks if intercepted Spring bean is in a transaction.
   */
  private boolean isTransactional() {
    try {
      return (Boolean) IS_TRANSACTION_ALIVE_METHOD.invoke(null, null);
    } catch (Exception e) {
      throw new RuntimeException("Could not check if the Spring bean is executing within a transaction", e);
    }
  }
}