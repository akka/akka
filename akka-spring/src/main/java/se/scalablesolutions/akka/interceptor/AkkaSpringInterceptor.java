package se.scalablesolutions.akka.interceptor;


import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import se.scalablesolutions.akka.actor.ActiveObjectAspect;
import se.scalablesolutions.akka.actor.AspectInit;
import se.scalablesolutions.akka.actor.AspectInitRegistry;
import se.scalablesolutions.akka.actor.Dispatcher;
import se.scalablesolutions.akka.util.AkkaSpringJoinPointWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;


public class AkkaSpringInterceptor extends ActiveObjectAspect implements MethodInterceptor,BeanPostProcessor{

    private final static String CLASSNAME = "org.springframework.transaction.support.TransactionSynchronizationManager";


	@Override
	public Object invoke(MethodInvocation methodInvocation) throws Throwable {

        //Determine transactional status
        boolean transactional = getTransactionStatus();

        
		Dispatcher dispatcher = new Dispatcher(transactional);
		dispatcher.start();
		AspectInitRegistry.register(methodInvocation.getThis(), new AspectInit(
			    methodInvocation.getThis().getClass(),
			    dispatcher,
			    1000));

		AkkaSpringJoinPointWrapper asjp = AkkaSpringJoinPointWrapper.createSpringAkkaAspectWerkzWrapper(methodInvocation);
		System.out.println("AkkaSpringInterceptor = " + Thread.currentThread());

		Object obj = this.invoke(asjp);

		dispatcher.stop();

		return obj;



	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String arg1)
			throws BeansException {

		return bean;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String arg1)
			throws BeansException {

		return bean;
	}

    /*
    * Checks if intercepted Spring bean is in a transaction
    *
     */

    private boolean getTransactionStatus() {
        String status = null;
        Boolean hasTransaction = null;
        try {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if (contextClassLoader != null) {
                Class tsmClass = contextClassLoader.loadClass(CLASSNAME);
                hasTransaction = (Boolean) tsmClass.getMethod("isActualTransactionActive", null).invoke(null, null);
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return hasTransaction;
    }
}
