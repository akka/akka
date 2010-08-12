/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring


import foo.{IMyPojo, MyPojo}
import se.scalablesolutions.akka.dispatch._

import org.scalatest.FeatureSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.ApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.core.io.{ClassPathResource, Resource}
import java.util.concurrent._

/**
 * Tests for spring configuration of typed actors.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class DispatcherSpringFeatureTest extends FeatureSpec with ShouldMatchers {
  val EVENT_DRIVEN_PREFIX = "akka:event-driven:dispatcher:"

  feature("Spring configuration") {

    scenario("get a executor-event-driven-dispatcher with array-blocking-queue from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("executor-event-driven-dispatcher-1").asInstanceOf[ExecutorBasedEventDrivenDispatcher]
      assert(dispatcher.name === EVENT_DRIVEN_PREFIX + "dispatcher-1")
      val executor = getThreadPoolExecutorAndAssert(dispatcher)
      assert(executor.getCorePoolSize() === 1)
      assert(executor.getMaximumPoolSize() === 20)
      assert(executor.getKeepAliveTime(TimeUnit.MILLISECONDS) === 3000)
      assert(executor.getQueue().isInstanceOf[ArrayBlockingQueue[Runnable]]);
      assert(executor.getQueue().remainingCapacity() === 100)
    }

    scenario("get a dispatcher via ref from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val pojo = context.getBean("typed-actor-with-dispatcher-ref").asInstanceOf[IMyPojo]
      assert(pojo != null)
    }

    scenario("get a executor-event-driven-dispatcher with bounded-linked-blocking-queue with unbounded capacity from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("executor-event-driven-dispatcher-2").asInstanceOf[ExecutorBasedEventDrivenDispatcher]
      val executor = getThreadPoolExecutorAndAssert(dispatcher)
      assert(executor.getQueue().isInstanceOf[LinkedBlockingQueue[Runnable]])
      assert(executor.getQueue().remainingCapacity() === Integer.MAX_VALUE)
      assert(dispatcher.name === EVENT_DRIVEN_PREFIX + "dispatcher-2")
    }

    scenario("get a executor-event-driven-dispatcher with unbounded-linked-blocking-queue with bounded capacity from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("executor-event-driven-dispatcher-4").asInstanceOf[ExecutorBasedEventDrivenDispatcher]
      assert(dispatcher.name === EVENT_DRIVEN_PREFIX + "dispatcher-4")
      val executor = getThreadPoolExecutorAndAssert(dispatcher)
      assert(executor.getQueue().isInstanceOf[LinkedBlockingQueue[Runnable]])
      assert(executor.getQueue().remainingCapacity() === 55)
    }

    scenario("get a executor-event-driven-dispatcher with unbounded-linked-blocking-queue with unbounded capacity from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("executor-event-driven-dispatcher-5").asInstanceOf[ExecutorBasedEventDrivenDispatcher]
      assert(dispatcher.name === EVENT_DRIVEN_PREFIX + "dispatcher-5")
      val executor = getThreadPoolExecutorAndAssert(dispatcher)
      assert(executor.getQueue().isInstanceOf[LinkedBlockingQueue[Runnable]])
      assert(executor.getQueue().remainingCapacity() === Integer.MAX_VALUE)
    }

    scenario("get a executor-event-driven-dispatcher with synchronous-queue from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("executor-event-driven-dispatcher-6").asInstanceOf[ExecutorBasedEventDrivenDispatcher]
      assert(dispatcher.name === EVENT_DRIVEN_PREFIX + "dispatcher-6")
      val executor = getThreadPoolExecutorAndAssert(dispatcher)
      assert(executor.getQueue().isInstanceOf[SynchronousQueue[Runnable]])
    }

    scenario("get a reactor-based-thread-pool-event-driven-dispatcher with synchronous-queue from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("reactor-based-thread-pool-event-driven-dispatcher").asInstanceOf[ReactorBasedThreadPoolEventDrivenDispatcher]
      val executor = getThreadPoolExecutorAndAssert(dispatcher)
      assert(executor.getQueue().isInstanceOf[SynchronousQueue[Runnable]])
    }

    scenario("get a reactor-based-single-thread-event-driven-dispatcher with synchronous-queue from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("reactor-based-single-thread-event-driven-dispatcher").asInstanceOf[ReactorBasedSingleThreadEventDrivenDispatcher]
      assert(dispatcher != null)
    }

    scenario("get a executor-based-event-driven-work-stealing-dispatcher from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("executor-based-event-driven-work-stealing-dispatcher").asInstanceOf[ExecutorBasedEventDrivenWorkStealingDispatcher]
      assert(dispatcher != null)
      assert(dispatcher.name === "akka:event-driven-work-stealing:dispatcher:workStealingDispatcher")
      val executor = getThreadPoolExecutorAndAssert(dispatcher)
      assert(executor.getQueue().isInstanceOf[BlockingQueue[Runnable]])
    }

    scenario("get a hawt-dispatcher from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val dispatcher = context.getBean("hawt-dispatcher").asInstanceOf[HawtDispatcher]
      assert(dispatcher != null)
      assert(dispatcher.toString === "HawtDispatchEventDrivenDispatcher")
      assert(dispatcher.aggregate === false)
    }

   scenario("get a thread-based-dispatcher from context") {
      val context = new ClassPathXmlApplicationContext("/dispatcher-config.xml")
      val pojo = context.getBean("typed-actor-with-thread-based-dispatcher").asInstanceOf[IMyPojo]
      assert(pojo != null)
    }

  }

  /**
   * get ThreadPoolExecutor via reflection and assert that dispatcher is correct type
   */
  private def getThreadPoolExecutorAndAssert(dispatcher: MessageDispatcher): ThreadPoolExecutor = {
    assert(dispatcher.isInstanceOf[ThreadPoolBuilder])
    val pool = dispatcher.asInstanceOf[ThreadPoolBuilder]
    val field = pool.getClass.getDeclaredField("se$scalablesolutions$akka$dispatch$ThreadPoolBuilder$$threadPoolBuilder")
    field.setAccessible(true)
    val executor = field.get(pool).asInstanceOf[ThreadPoolExecutor]
    assert(executor != null)
    executor;
  }

}