/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import se.scalablesolutions.akka.dispatch.ExecutorBasedEventDrivenDispatcher;
import se.scalablesolutions.akka.dispatch.ReactorBasedThreadPoolEventDrivenDispatcher;
import se.scalablesolutions.akka.dispatch.ReactorBasedSingleThreadEventDrivenDispatcher;
import se.scalablesolutions.akka.dispatch.MessageDispatcher;
import se.scalablesolutions.akka.dispatch.ThreadPoolBuilder;

import se.scalablesolutions.akka.spring.foo.MyPojo;

/**
 * Tests for spring configuration of dispatcher configuration.
 * @author michaelkober
 */
public class DispatcherConfigurationTest {

        private ApplicationContext context = null;

        @Before
        public void setUp() {
        context = new ClassPathXmlApplicationContext("se/scalablesolutions/akka/spring/foo/dispatcher-config.xml");
        }

        /**
         * test for executor-event-driven-dispatcher with array-blocking-queue
         */
        @Test
        public void testDispatcher() {
                MessageDispatcher dispatcher = (MessageDispatcher) context.getBean("executor-event-driven-dispatcher-1");
                ThreadPoolExecutor executor = getThreadPoolExecutorAndAssert(dispatcher);
                assertEquals("wrong core pool size", 1, executor.getCorePoolSize());
                assertEquals("wrong max pool size", 20, executor.getMaximumPoolSize());
                assertEquals("wrong keep alive", 3000, executor.getKeepAliveTime(TimeUnit.MILLISECONDS));
                assertTrue("wrong queue type",executor.getQueue() instanceof ArrayBlockingQueue);
                assertEquals("wrong capacity", 100, executor.getQueue().remainingCapacity());
        }

    /**
         * test for dispatcher via ref
         */
        @Test
        public void testDispatcherRef() {
      MyPojo pojo = (MyPojo) context.getBean("typed-actor-with-dispatcher-ref");
      assertNotNull(pojo);
        }

        /**
         * test for executor-event-driven-dispatcher with bounded-linked-blocking-queue with unbounded capacity
         */
        @Test
        public void testDispatcherWithBoundedLinkedBlockingQueueWithUnboundedCapacity() {
                MessageDispatcher dispatcher = (MessageDispatcher) context.getBean("executor-event-driven-dispatcher-2");
                ThreadPoolExecutor executor = getThreadPoolExecutorAndAssert(dispatcher);
                assertTrue("wrong queue type", executor.getQueue() instanceof LinkedBlockingQueue);
                assertEquals("wrong capacity", Integer.MAX_VALUE, executor.getQueue().remainingCapacity());
        }

    /**
         * test for executor-event-driven-dispatcher with unbounded-linked-blocking-queue with bounded capacity
         */
        @Test
        public void testDispatcherWithLinkedBlockingQueueWithBoundedCapacity() {
                MessageDispatcher dispatcher = (MessageDispatcher) context.getBean("executor-event-driven-dispatcher-4");
                ThreadPoolExecutor executor = getThreadPoolExecutorAndAssert(dispatcher);
                assertTrue("wrong queue type", executor.getQueue() instanceof LinkedBlockingQueue);
                assertEquals("wrong capacity", 55, executor.getQueue().remainingCapacity());
        }

    /**
         * test for executor-event-driven-dispatcher with unbounded-linked-blocking-queue with unbounded capacity
         */
        @Test
        public void testDispatcherWithLinkedBlockingQueueWithUnboundedCapacity() {
                MessageDispatcher dispatcher = (MessageDispatcher) context.getBean("executor-event-driven-dispatcher-5");
                ThreadPoolExecutor executor = getThreadPoolExecutorAndAssert(dispatcher);
                assertTrue("wrong queue type", executor.getQueue() instanceof LinkedBlockingQueue);
                assertEquals("wrong capacity", Integer.MAX_VALUE, executor.getQueue().remainingCapacity());
        }

    /**
     * test for executor-event-driven-dispatcher with synchronous-queue
     */
    @Test
    public void testDispatcherWithSynchronousQueue() {
        MessageDispatcher dispatcher = (MessageDispatcher) context.getBean("executor-event-driven-dispatcher-6");
        ThreadPoolExecutor executor = getThreadPoolExecutorAndAssert(dispatcher);
        assertTrue("wrong queue type", executor.getQueue() instanceof SynchronousQueue);
    }

    /**
     * test for reactor-based-thread-pool-event-driven-dispatcher with synchronous-queue
     */
    @Test
    public void testReactorBasedThreadPoolDispatcherWithSynchronousQueue() {
        MessageDispatcher dispatcher = (MessageDispatcher) context.getBean("reactor-based-thread-pool-event-driven-dispatcher");
        assertTrue(dispatcher instanceof ReactorBasedThreadPoolEventDrivenDispatcher);
                assertTrue(dispatcher instanceof ThreadPoolBuilder);
                ThreadPoolBuilder pool = (ThreadPoolBuilder) dispatcher;
                ThreadPoolExecutor executor = pool.se$scalablesolutions$akka$dispatch$ThreadPoolBuilder$$threadPoolBuilder();
                assertNotNull(executor);
        assertTrue("wrong queue type", executor.getQueue() instanceof SynchronousQueue);
    }

    /**
     * test for reactor-based-single-thread-event-driven-dispatcher with synchronous-queue
     */
    @Test
    public void testReactorBasedSingleThreadDispatcherWithSynchronousQueue() {
        MessageDispatcher dispatcher = (MessageDispatcher) context.getBean("reactor-based-single-thread-event-driven-dispatcher");
        assertTrue(dispatcher instanceof ReactorBasedSingleThreadEventDrivenDispatcher);
    }

    /**
         * Assert that dispatcher is correct type and get executor.
         */
        private ThreadPoolExecutor getThreadPoolExecutorAndAssert(MessageDispatcher dispatcher) {
                assertTrue(dispatcher instanceof ExecutorBasedEventDrivenDispatcher);
                assertTrue(dispatcher instanceof ThreadPoolBuilder);
                ThreadPoolBuilder pool = (ThreadPoolBuilder) dispatcher;
                ThreadPoolExecutor executor = pool.se$scalablesolutions$akka$dispatch$ThreadPoolBuilder$$threadPoolBuilder();
                assertNotNull(executor);
                return executor;
        }

}
