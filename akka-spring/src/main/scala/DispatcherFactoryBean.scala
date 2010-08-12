/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.config.AbstractFactoryBean
import se.scalablesolutions.akka.config.JavaConfig._
import AkkaSpringConfigurationTags._
import reflect.BeanProperty
import se.scalablesolutions.akka.dispatch.{ThreadPoolBuilder, Dispatchers, MessageDispatcher}
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor.{DiscardPolicy, DiscardOldestPolicy, CallerRunsPolicy, AbortPolicy}

/**
 * Reusable factory method for dispatchers.
 */
object DispatcherFactoryBean {
  def createNewInstance(properties: DispatcherProperties): MessageDispatcher = {
    var dispatcher = properties.dispatcherType match {
      case EXECUTOR_BASED_EVENT_DRIVEN => Dispatchers.newExecutorBasedEventDrivenDispatcher(properties.name)
      case EXECUTOR_BASED_EVENT_DRIVEN_WORK_STEALING => Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher(properties.name)
      case REACTOR_BASED_THREAD_POOL_EVENT_DRIVEN => Dispatchers.newReactorBasedThreadPoolEventDrivenDispatcher(properties.name)
      case REACTOR_BASED_SINGLE_THREAD_EVENT_DRIVEN => Dispatchers.newReactorBasedSingleThreadEventDrivenDispatcher(properties.name)
      case THREAD_BASED => throw new IllegalArgumentException("not implemented yet") //FIXME
      case HAWT => Dispatchers.newHawtDispatcher(properties.aggregate)
      case _ => throw new IllegalArgumentException("unknown dispatcher type")
    }
   if ((properties.threadPool != null) && (properties.threadPool.queue != null)) {
        var threadPoolBuilder = dispatcher.asInstanceOf[ThreadPoolBuilder]
        threadPoolBuilder = properties.threadPool.queue match {
        case VAL_BOUNDED_ARRAY_BLOCKING_QUEUE => threadPoolBuilder.withNewThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(properties.threadPool.capacity, properties.threadPool.fairness)
        case VAL_UNBOUNDED_LINKED_BLOCKING_QUEUE => if (properties.threadPool.capacity > -1)
          threadPoolBuilder.withNewThreadPoolWithLinkedBlockingQueueWithCapacity(properties.threadPool.capacity)
        else
          threadPoolBuilder.withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
        case VAL_BOUNDED_LINKED_BLOCKING_QUEUE => threadPoolBuilder.withNewBoundedThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity(properties.threadPool.bound)
        case VAL_SYNCHRONOUS_QUEUE => threadPoolBuilder.withNewThreadPoolWithSynchronousQueueWithFairness(properties.threadPool.fairness)
        case _ => throw new IllegalArgumentException("unknown queue type")
      }
      if (properties.threadPool.corePoolSize > -1) {
        threadPoolBuilder.setCorePoolSize(properties.threadPool.corePoolSize)
      }
      if (properties.threadPool.maxPoolSize > -1) {
        threadPoolBuilder.setMaxPoolSize(properties.threadPool.maxPoolSize)
      }
      if (properties.threadPool.keepAlive > -1) {
        threadPoolBuilder.setKeepAliveTimeInMillis(properties.threadPool.keepAlive)
      }
      if ((properties.threadPool.rejectionPolicy != null) && (!properties.threadPool.rejectionPolicy.isEmpty)) {
        val policy: RejectedExecutionHandler = properties.threadPool.rejectionPolicy match {
          case "abort-policy" => new AbortPolicy()
          case "caller-runs-policy" => new CallerRunsPolicy()
          case "discard-oldest-policy" => new DiscardOldestPolicy()
          case "discard-policy" => new DiscardPolicy()
          case _ => throw new IllegalArgumentException("Unknown rejection-policy '" + properties.threadPool.rejectionPolicy + "'")
        }
        threadPoolBuilder.setRejectionPolicy(policy)
      }
      threadPoolBuilder.asInstanceOf[MessageDispatcher]
    } else {
      dispatcher
    }
  }
}

/**
 * Factory bean for supervisor configuration.
 * @author michaelkober
 */
class DispatcherFactoryBean extends AbstractFactoryBean[MessageDispatcher] {
  @BeanProperty var properties: DispatcherProperties = _

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
