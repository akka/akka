package se.scalablesolutions.akka.api;

import static se.scalablesolutions.akka.actor.ActiveObject.link;
import static se.scalablesolutions.akka.actor.ActiveObject.newInstance;

import org.junit.Assert;
import org.junit.Test;

import se.scalablesolutions.akka.config.OneForOneStrategy;
import junit.framework.TestCase;

/**
 * <p>Small misc tests that do not fit anywhere else and does not require a separate testcase</p>
 * 
 * @author johanrask
 *
 */
public class MiscActiveObjectTest extends TestCase {

        
        /**
         * Verifies that both preRestart and postRestart methods are invoked when
         * an actor is restarted
         */
        public void testFailingPostRestartInvocation() throws InterruptedException {
                SimpleJavaPojo pojo = newInstance(SimpleJavaPojo.class,500);
                SimpleJavaPojo supervisor = newInstance(SimpleJavaPojo.class,500);
                link(supervisor,pojo,new OneForOneStrategy(3, 2000),new Class[]{Throwable.class});
                pojo.throwException();
                Thread.sleep(500);
                Assert.assertTrue(pojo.pre);
                Assert.assertTrue(pojo.post);
        }
        
}
