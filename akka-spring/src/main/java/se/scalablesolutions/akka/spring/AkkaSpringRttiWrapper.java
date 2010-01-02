/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring;

import org.aopalliance.intercept.MethodInvocation;

import org.codehaus.aspectwerkz.joinpoint.MethodRtti;
import org.codehaus.aspectwerkz.joinpoint.Rtti;

import java.lang.reflect.Method;

public class AkkaSpringRttiWrapper implements MethodRtti {

  private MethodInvocation methodInvocation = null;

  public AkkaSpringRttiWrapper(MethodInvocation methodInvocation) {
    this.methodInvocation = methodInvocation;
  }

  public Method getMethod() {
    return methodInvocation.getMethod();
  }

  public Class getReturnType() {
    throw new UnsupportedOperationException();
  }

  public Object getReturnValue() {
    throw new UnsupportedOperationException();
  }

  public Class[] getExceptionTypes() {
    throw new UnsupportedOperationException();
  }

  public Class[] getParameterTypes() {
    throw new UnsupportedOperationException();
  }

  public Object[] getParameterValues() {
    throw new UnsupportedOperationException();
  }

  public void setParameterValues(Object[] arg0) {
    throw new UnsupportedOperationException();
  }

  public Rtti cloneFor(Object arg0, Object arg1) {
    throw new UnsupportedOperationException();
  }

  public Class getDeclaringType() {
    throw new UnsupportedOperationException();
  }

  public int getModifiers() {
    throw new UnsupportedOperationException();
  }

  public String getName() {
    throw new UnsupportedOperationException();
  }

  public Object getTarget() {
    throw new UnsupportedOperationException();
  }

  public Object getThis() {
    throw new UnsupportedOperationException();
  }
}
