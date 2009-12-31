package se.scalablesolutions.akka.util;


import org.aopalliance.intercept.MethodInvocation;
import org.codehaus.aspectwerkz.joinpoint.*;
import org.codehaus.aspectwerkz.joinpoint.management.JoinPointType;


public class AkkaSpringJoinPointWrapper implements JoinPoint{

	private MethodInvocation methodInvocation = null;

	public MethodInvocation getMethodInvocation() {
		return methodInvocation;
	}

	public void setMethodInvocation(MethodInvocation methodInvocation) {
		this.methodInvocation = methodInvocation;
	}

	public static AkkaSpringJoinPointWrapper createSpringAkkaAspectWerkzWrapper(MethodInvocation methodInvocation){
		AkkaSpringJoinPointWrapper asjp = new AkkaSpringJoinPointWrapper();
		asjp.setMethodInvocation(methodInvocation);
		return asjp;
	}

	@Override
	public Object getCallee() {
		System.out.println("public Object getCallee()");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getCaller() {
		System.out.println("public Object getCaller() ");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Rtti getRtti() {
		System.out.println("public Rtti getRtti()");
		AkkaSpringRttiWrapper asrw = new AkkaSpringRttiWrapper(methodInvocation);
		return asrw;
	}

	@Override
	public Object getTarget() {
		System.out.println("public Object getTarget() ");
		return methodInvocation.getThis();
	}

	@Override
	public Object getThis() {
		System.out.println("public Object getThis()");
		return methodInvocation.getThis();
	}

	@Override
	public void addMetaData(Object arg0, Object arg1) {
		System.out.println("public void addMetaData(Object arg0, Object arg1)");
		// TODO Auto-generated method stub

	}

	@Override
	public Class getCalleeClass() {
		System.out.println("public Class getCalleeClass()");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Class getCallerClass() {
		System.out.println("public Class getCallerClass() ");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public EnclosingStaticJoinPoint getEnclosingStaticJoinPoint() {
		System.out.println("public EnclosingStaticJoinPoint getEnclosingStaticJoinPoint()");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getMetaData(Object arg0) {
		System.out.println("public Object getMetaData(Object arg0) ");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Signature getSignature() {
		System.out.println("public Signature getSignature() ");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Class getTargetClass() {
		System.out.println("public Class getTargetClass() ");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public JoinPointType getType() {
		System.out.println("public JoinPointType getType()");
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object proceed() throws Throwable {
		System.out.println("public Object proceed()");

		return methodInvocation.proceed();
	}

	
	public StaticJoinPoint copy() {
		// TODO Auto-generated method stub
		return null;
	}


}
