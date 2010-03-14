/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring;

import org.aopalliance.intercept.MethodInvocation;

import org.codehaus.aspectwerkz.joinpoint.*;
import org.codehaus.aspectwerkz.joinpoint.management.JoinPointType;

public class AkkaSpringJoinPointWrapper implements JoinPoint {

  private MethodInvocation methodInvocation = null;

  public static AkkaSpringJoinPointWrapper createSpringAkkaAspectWerkzWrapper(MethodInvocation methodInvocation) {
    AkkaSpringJoinPointWrapper joinPointWrapper = new AkkaSpringJoinPointWrapper();
    joinPointWrapper.setMethodInvocation(methodInvocation);
    return joinPointWrapper;
  }

  public MethodInvocation getMethodInvocation() {
    return methodInvocation;
  }

  public void setMethodInvocation(MethodInvocation methodInvocation) {
    this.methodInvocation = methodInvocation;
  }

  public Object proceed() throws Throwable {
    return methodInvocation.proceed();
  }

  public Rtti getRtti() {
    return new AkkaSpringRttiWrapper(methodInvocation);
  }

  public Object getTarget() {
    return methodInvocation.getThis();
  }

  public Object getThis() {
    return methodInvocation.getThis();
  }

  public Object getCallee() {
    throw new UnsupportedOperationException();
  }

  public Object getCaller() {
    throw new UnsupportedOperationException();
  }

  public void addMetaData(Object arg0, Object arg1) {
    throw new UnsupportedOperationException();
  }

  public Class getCalleeClass() {
    throw new UnsupportedOperationException();
  }

  public Class getCallerClass() {
    throw new UnsupportedOperationException();
  }

  public EnclosingStaticJoinPoint getEnclosingStaticJoinPoint() {
    throw new UnsupportedOperationException();
  }

  public Object getMetaData(Object arg0) {
    throw new UnsupportedOperationException();
  }

  public Signature getSignature() {
    throw new UnsupportedOperationException();
  }

  public Class getTargetClass() {
    throw new UnsupportedOperationException();
  }

  public JoinPointType getType() {
    throw new UnsupportedOperationException();
  }

  public StaticJoinPoint copy() {
    throw new UnsupportedOperationException();
  }
}
