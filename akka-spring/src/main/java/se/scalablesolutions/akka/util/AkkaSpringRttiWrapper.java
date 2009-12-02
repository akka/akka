package se.scalablesolutions.akka.util;


import org.aopalliance.intercept.MethodInvocation;
import org.codehaus.aspectwerkz.joinpoint.MethodRtti;
import org.codehaus.aspectwerkz.joinpoint.Rtti;

import java.lang.reflect.Method;

public class AkkaSpringRttiWrapper implements MethodRtti {

	private MethodInvocation methodInvocation = null;

	public AkkaSpringRttiWrapper(MethodInvocation methodInvocation){
		this.methodInvocation = methodInvocation;

	}

	@Override
	public Method getMethod() {
		return methodInvocation.getMethod();
	}

	@Override
	public Class getReturnType() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getReturnValue() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Class[] getExceptionTypes() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Class[] getParameterTypes() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object[] getParameterValues() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setParameterValues(Object[] arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public Rtti cloneFor(Object arg0, Object arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Class getDeclaringType() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getModifiers() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getTarget() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getThis() {
		// TODO Auto-generated method stub
		return null;
	}
}
