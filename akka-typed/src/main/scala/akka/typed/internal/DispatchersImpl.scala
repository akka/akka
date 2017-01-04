/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import scala.concurrent.ExecutionContextExecutor
import java.util.concurrent.{ Executors, ExecutorService }
import akka.event.LoggingAdapter
import akka.{ actor ⇒ a, dispatch ⇒ d, event ⇒ e }
import java.util.concurrent.ConcurrentHashMap
import akka.ConfigurationException
import com.typesafe.config.{ Config, ConfigFactory }
import akka.dispatch.ExecutionContexts
import java.util.concurrent.ConcurrentSkipListSet
import java.util.Comparator

class DispatchersImpl(settings: Settings, log: LoggingAdapter, prerequisites: d.DispatcherPrerequisites) extends Dispatchers {

  def lookup(selector: DispatcherSelector): ExecutionContextExecutor =
    selector match {
      case DispatcherDefault(_) ⇒ defaultGlobalDispatcher
      case DispatcherFromConfig(path, _) ⇒ lookup(path)
      case DispatcherFromExecutor(ex: ExecutionContextExecutor, _) ⇒ ex
      case DispatcherFromExecutor(ex, _) ⇒ d.ExecutionContexts.fromExecutor(ex)
      case DispatcherFromExecutionContext(ec: ExecutionContextExecutor, _) ⇒ ec
      case DispatcherFromExecutionContext(ec, _) ⇒ throw new UnsupportedOperationException("I thought all ExecutionContexts are also Executors?") // FIXME
    }

  def shutdown(): Unit = {
    val i = allCreatedServices.iterator()
    while (i.hasNext) i.next().shutdown()
    allCreatedServices.clear()
  }

  import Dispatchers._

  val cachingConfig = new d.CachingConfig(settings.config)

  val defaultDispatcherConfig: Config =
    idConfig(DefaultDispatcherId).withFallback(settings.config.getConfig(DefaultDispatcherId))

  /**
   * The one and only default dispatcher.
   */
  def defaultGlobalDispatcher: ExecutionContextExecutor = lookup(DefaultDispatcherId)

  private val dispatcherConfigurators = new ConcurrentHashMap[String, MessageDispatcherConfigurator]
  private val allCreatedServices = new ConcurrentSkipListSet[ExecutorService](new Comparator[ExecutorService] {
    override def compare(left: ExecutorService, right: ExecutorService): Int = {
      val l = if (left == null) 0 else left.hashCode
      val r = if (right == null) 0 else right.hashCode
      if (l < r) -1 else if (l > r) 1 else 0
    }
  })

  /**
   * Returns a dispatcher as specified in configuration. Please note that this
   * method _may_ create and return a NEW dispatcher, _every_ call.
   *
   * Throws ConfigurationException if the specified dispatcher cannot be found in the configuration.
   */
  def lookup(id: String): ExecutionContextExecutor =
    lookupConfigurator(id).dispatcher() match {
      case es: ExecutorService ⇒
        allCreatedServices.add(es)
        es
      case ece ⇒ ece
    }

  /**
   * Checks that the configuration provides a section for the given dispatcher.
   * This does not guarantee that no ConfigurationException will be thrown when
   * using this dispatcher, because the details can only be checked by trying
   * to instantiate it, which might be undesirable when just checking.
   */
  def hasDispatcher(id: String): Boolean = dispatcherConfigurators.containsKey(id) || cachingConfig.hasPath(id)

  private def lookupConfigurator(id: String): MessageDispatcherConfigurator = {
    dispatcherConfigurators.get(id) match {
      case null ⇒
        // It doesn't matter if we create a dispatcher configurator that isn't used due to concurrent lookup.
        // That shouldn't happen often and in case it does the actual ExecutorService isn't
        // created until used, i.e. cheap.
        val newConfigurator =
          if (cachingConfig.hasPath(id)) configuratorFrom(config(id))
          else throw new ConfigurationException(s"Dispatcher [$id] not configured")

        dispatcherConfigurators.putIfAbsent(id, newConfigurator) match {
          case null     ⇒ newConfigurator
          case existing ⇒ existing
        }

      case existing ⇒ existing
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def config(id: String): Config = {
    import scala.collection.JavaConverters._
    def simpleName = id.substring(id.lastIndexOf('.') + 1)
    idConfig(id)
      .withFallback(settings.config.getConfig(id))
      .withFallback(ConfigFactory.parseMap(Map("name" → simpleName).asJava))
      .withFallback(defaultDispatcherConfig)
  }

  private def idConfig(id: String): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(Map("id" → id).asJava)
  }

  /**
   * INTERNAL API
   *
   * Creates a MessageDispatcherConfigurator from a Config.
   *
   * The Config must also contain a `id` property, which is the identifier of the dispatcher.
   *
   * Throws: IllegalArgumentException if the value of "type" is not valid
   *         IllegalArgumentException if it cannot create the MessageDispatcherConfigurator
   */
  private def configuratorFrom(cfg: Config): MessageDispatcherConfigurator = {
    if (!cfg.hasPath("id")) throw new ConfigurationException("Missing dispatcher 'id' property in config: " + cfg.root.render)

    cfg.getString("type") match {
      case "Dispatcher"       ⇒ new DispatcherConfigurator(cfg, prerequisites)
      case "PinnedDispatcher" ⇒ new PinnedDispatcherConfigurator(cfg, prerequisites)
      case fqn ⇒
        val args = List(classOf[Config] → cfg)
        prerequisites.dynamicAccess.createInstanceFor[DispatcherConfigurator](fqn, args).recover({
          case exception ⇒
            throw new ConfigurationException(
              ("Cannot instantiate DispatcherConfigurator type [%s], defined in [%s], " +
                "make sure it has constructor with [com.typesafe.config.Config] and " +
                "[akka.dispatch.DispatcherPrerequisites] parameters")
                .format(fqn, cfg.getString("id")), exception)
        }).get
    }
  }
}

/**
 * Base class to be used for hooking in new dispatchers into Dispatchers.
 */
abstract class MessageDispatcherConfigurator(_config: Config, val prerequisites: d.DispatcherPrerequisites) {

  val config: Config = new d.CachingConfig(_config)

  /**
   * Returns an instance of MessageDispatcher given the configuration.
   * Depending on the needs the implementation may return a new instance for
   * each invocation or return the same instance every time.
   */
  def dispatcher(): ExecutionContextExecutor

  def configureExecutor(): d.ExecutorServiceConfigurator = {
    def configurator(executor: String): d.ExecutorServiceConfigurator = executor match {
      case null | "" | "fork-join-executor" ⇒ new d.ForkJoinExecutorConfigurator(config.getConfig("fork-join-executor"), prerequisites)
      case "thread-pool-executor"           ⇒ new d.ThreadPoolExecutorConfigurator(config.getConfig("thread-pool-executor"), prerequisites)
      case fqcn ⇒
        val args = List(
          classOf[Config] → config,
          classOf[d.DispatcherPrerequisites] → prerequisites)
        prerequisites.dynamicAccess.createInstanceFor[d.ExecutorServiceConfigurator](fqcn, args).recover({
          case exception ⇒ throw new IllegalArgumentException(
            ("Cannot instantiate ExecutorServiceConfigurator (\"executor = [%s]\"), defined in [%s], " +
              "make sure it has an accessible constructor with a [%s,%s] signature")
              .format(fqcn, config.getString("id"), classOf[Config], classOf[d.DispatcherPrerequisites]), exception)
        }).get
    }

    config.getString("executor") match {
      case "default-executor" ⇒ new d.DefaultExecutorServiceConfigurator(config.getConfig("default-executor"), prerequisites, configurator(config.getString("default-executor.fallback")))
      case other              ⇒ configurator(other)
    }
  }
}

/**
 * Configurator for creating [[akka.dispatch.Dispatcher]].
 * Returns the same dispatcher instance for for each invocation
 * of the `dispatcher()` method.
 */
class DispatcherConfigurator(config: Config, prerequisites: d.DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val instance = ExecutionContexts.fromExecutorService(
    configureExecutor().createExecutorServiceFactory(config.getString("id"), prerequisites.threadFactory)
    .createExecutorService)

  /**
   * Returns the same dispatcher instance for each invocation
   */
  override def dispatcher(): ExecutionContextExecutor = instance
}

/**
 * Configurator for creating [[akka.dispatch.PinnedDispatcher]].
 * Returns new dispatcher instance for for each invocation
 * of the `dispatcher()` method.
 */
class PinnedDispatcherConfigurator(config: Config, prerequisites: d.DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val threadPoolConfig: d.ThreadPoolConfig = configureExecutor() match {
    case e: d.ThreadPoolExecutorConfigurator ⇒ e.threadPoolConfig
    case other ⇒
      prerequisites.eventStream.publish(
        e.Logging.Warning(
          "PinnedDispatcherConfigurator",
          this.getClass,
          "PinnedDispatcher [%s] not configured to use ThreadPoolExecutor, falling back to default config.".format(
            config.getString("id"))))
      d.ThreadPoolConfig()
  }

  private val factory = threadPoolConfig.createExecutorServiceFactory(config.getString("id"), prerequisites.threadFactory)

  /**
   * Creates new dispatcher for each invocation.
   */
  override def dispatcher(): ExecutionContextExecutor = ExecutionContexts.fromExecutorService(factory.createExecutorService)

}
