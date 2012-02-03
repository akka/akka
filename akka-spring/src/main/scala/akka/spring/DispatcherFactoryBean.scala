/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.springframework.beans.factory.config.AbstractFactoryBean
import AkkaSpringConfigurationTags._
import reflect.BeanProperty
import akka.actor.ActorRef
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor.{ DiscardPolicy, DiscardOldestPolicy, CallerRunsPolicy, AbortPolicy }
import akka.dispatch._
import scala.util.Duration

/**
 * Reusable factory method for dispatchers.
 */
object DispatcherFactoryBean {

  /**
   * factory method for dispatchers
   * @param properties dispatcher properties
   * @param actorRef actorRef needed for thread based dispatcher
   */
  def createNewInstance(properties: DispatcherProperties, actorRef: Option[ActorRef] = None): MessageDispatcher = {

    //Creates a ThreadPoolConfigDispatcherBuilder and applies the configuration to it
    def configureThreadPool(createDispatcher: ⇒ (ThreadPoolConfig) ⇒ MessageDispatcher): ThreadPoolConfigDispatcherBuilder = {
      if ((properties.threadPool ne null) && (properties.threadPool.queue ne null)) {
        import ThreadPoolConfigDispatcherBuilder.conf_?
        import properties._
        val queueDef = Some(threadPool.queue)
        val corePoolSize = if (threadPool.corePoolSize > -1) Some(threadPool.corePoolSize) else None
        val maxPoolSize = if (threadPool.maxPoolSize > -1) Some(threadPool.maxPoolSize) else None
        val keepAlive = if (threadPool.keepAlive > -1) Some(threadPool.keepAlive) else None
        val executorBounds = if (threadPool.bound > -1) Some(threadPool.bound) else None
        val flowHandler = threadPool.rejectionPolicy match { //REMOVE THIS FROM THE CONFIG
          case null | ""               ⇒ None
          case "abort-policy"          ⇒ Some(new AbortPolicy())
          case "caller-runs-policy"    ⇒ Some(new CallerRunsPolicy())
          case "discard-oldest-policy" ⇒ Some(new DiscardOldestPolicy())
          case "discard-policy"        ⇒ Some(new DiscardPolicy())
          case x                       ⇒ throw new IllegalArgumentException("Unknown rejection-policy '" + x + "'")
        }

        //Apply the following options to the config if they are present in the cfg
        ThreadPoolConfigDispatcherBuilder(createDispatcher, ThreadPoolConfig()).configure(
          conf_?(queueDef)(definition ⇒ definition match {
            case VAL_BOUNDED_ARRAY_BLOCKING_QUEUE ⇒
              _.withNewThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(threadPool.capacity, threadPool.fairness)
            case VAL_UNBOUNDED_LINKED_BLOCKING_QUEUE if threadPool.capacity > -1 ⇒
              _.withNewThreadPoolWithLinkedBlockingQueueWithCapacity(threadPool.capacity)
            case VAL_UNBOUNDED_LINKED_BLOCKING_QUEUE if threadPool.capacity <= 0 ⇒
              _.withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
            case VAL_BOUNDED_LINKED_BLOCKING_QUEUE ⇒
              _.withNewBoundedThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity(threadPool.bound)
            case VAL_SYNCHRONOUS_QUEUE ⇒
              _.withNewThreadPoolWithSynchronousQueueWithFairness(threadPool.fairness)
            case unknown ⇒
              throw new IllegalArgumentException("Unknown queue type " + unknown)
          }),
          conf_?(keepAlive)(time ⇒ _.setKeepAliveTimeInMillis(time)),
          conf_?(corePoolSize)(count ⇒ _.setCorePoolSize(count)),
          conf_?(maxPoolSize)(count ⇒ _.setMaxPoolSize(count)),
          conf_?(executorBounds)(bounds ⇒ _.setExecutorBounds(bounds)),
      } else
        ThreadPoolConfigDispatcherBuilder(createDispatcher, ThreadPoolConfig())
    }

    //Create the dispatcher
    properties.dispatcherType match {
      case EXECUTOR_BASED_EVENT_DRIVEN ⇒
        configureThreadPool(poolConfig ⇒
          new ExecutorBasedEventDrivenDispatcher(properties.name, poolConfig)).build
      case EXECUTOR_BASED_EVENT_DRIVEN_WORK_STEALING ⇒
        configureThreadPool(poolConfig ⇒
          new ExecutorBasedEventDrivenWorkStealingDispatcher(
            properties.name,
            Dispatchers.THROUGHPUT,
            Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
            Dispatchers.MAILBOX_TYPE,
            poolConfig)).build
      case THREAD_BASED if actorRef.isEmpty ⇒
        throw new IllegalArgumentException("Need an ActorRef to create a thread based dispatcher.")
      case THREAD_BASED if actorRef.isDefined ⇒
        Dispatchers.newThreadBasedDispatcher(actorRef.get)
      case unknown ⇒
        throw new IllegalArgumentException("Unknown dispatcher type " + unknown)
    }
  }
}

/**
 * Factory bean for supervisor configuration.
 * @author michaelkober
 */
class DispatcherFactoryBean extends AbstractFactoryBean[MessageDispatcher] {
  @BeanProperty
  var properties: DispatcherProperties = _

  /*
   * @see org.springframework.beans.factory.FactoryBean#getObjectType()
   */
  def getObjectType: Class[MessageDispatcher] = classOf[MessageDispatcher]

  /*
   * @see org.springframework.beans.factory.config.AbstractFactoryBean#createInstance()
   */
  def createInstance: MessageDispatcher = {
    import DispatcherFactoryBean._
    createNewInstance(properties)
  }
}
