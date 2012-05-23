/**
 *   Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent.{ ConcurrentHashMap, TimeUnit, ThreadFactory }

import scala.collection.JavaConverters.mapAsJavaMapConverter

import com.typesafe.config.{ ConfigFactory, Config }

import Dispatchers.DefaultDispatcherId
import akka.actor.{ Scheduler, DynamicAccess, ActorSystem }
import akka.event.Logging.Warning
import akka.event.EventStream
import akka.util.Duration

trait DispatcherPrerequisites {
  def threadFactory: ThreadFactory
  def eventStream: EventStream
  def deadLetterMailbox: Mailbox
  def scheduler: Scheduler
  def dynamicAccess: DynamicAccess
  def settings: ActorSystem.Settings
}

case class DefaultDispatcherPrerequisites(
  val threadFactory: ThreadFactory,
  val eventStream: EventStream,
  val deadLetterMailbox: Mailbox,
  val scheduler: Scheduler,
  val dynamicAccess: DynamicAccess,
  val settings: ActorSystem.Settings) extends DispatcherPrerequisites

object Dispatchers {
  /**
   * The id of the default dispatcher, also the full key of the
   * configuration of the default dispatcher.
   */
  final val DefaultDispatcherId = "akka.actor.default-dispatcher"
}

/**
 * Dispatchers are to be defined in configuration to allow for tuning
 * for different environments. Use the `lookup` method to create
 * a dispatcher as specified in configuration.
 *
 * Look in `akka.actor.default-dispatcher` section of the reference.conf
 * for documentation of dispatcher options.
 */
class Dispatchers(val settings: ActorSystem.Settings, val prerequisites: DispatcherPrerequisites) {

  import Dispatchers._

  val defaultDispatcherConfig: Config =
    idConfig(DefaultDispatcherId).withFallback(settings.config.getConfig(DefaultDispatcherId))

  /**
   * The one and only default dispatcher.
   */
  def defaultGlobalDispatcher: MessageDispatcher = lookup(DefaultDispatcherId)

  private val dispatcherConfigurators = new ConcurrentHashMap[String, MessageDispatcherConfigurator]

  /**
   * Returns a dispatcher as specified in configuration, or if not defined it uses
   * the default dispatcher. Please note that this method _may_ create and return a NEW dispatcher,
   * _every_ call.
   */
  def lookup(id: String): MessageDispatcher = lookupConfigurator(id).dispatcher()

  private def lookupConfigurator(id: String): MessageDispatcherConfigurator = {
    dispatcherConfigurators.get(id) match {
      case null ⇒
        // It doesn't matter if we create a dispatcher configurator that isn't used due to concurrent lookup.
        // That shouldn't happen often and in case it does the actual ExecutorService isn't
        // created until used, i.e. cheap.
        val newConfigurator =
          if (settings.config.hasPath(id)) {
            configuratorFrom(config(id))
          } else {
            // Note that the configurator of the default dispatcher will be registered for this id,
            // so this will only be logged once, which is crucial.
            prerequisites.eventStream.publish(Warning("Dispatchers", this.getClass,
              "Dispatcher [%s] not configured, using default-dispatcher".format(id)))
            lookupConfigurator(DefaultDispatcherId)
          }

        dispatcherConfigurators.putIfAbsent(id, newConfigurator) match {
          case null     ⇒ newConfigurator
          case existing ⇒ existing
        }

      case existing ⇒ existing
    }
  }

  private def config(id: String): Config = {
    import scala.collection.JavaConverters._
    def simpleName = id.substring(id.lastIndexOf('.') + 1)
    idConfig(id)
      .withFallback(settings.config.getConfig(id))
      .withFallback(ConfigFactory.parseMap(Map("name" -> simpleName).asJava))
      .withFallback(defaultDispatcherConfig)
  }

  private def idConfig(id: String): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(Map("id" -> id).asJava)
  }

  /*
   * Creates a dispatcher from a Config. Internal test purpose only.
   *
   * ex: from(config.getConfig(id))
   *
   * The Config must also contain a `id` property, which is the identifier of the dispatcher.
   *
   * Throws: IllegalArgumentException if the value of "type" is not valid
   *         IllegalArgumentException if it cannot create the MessageDispatcherConfigurator
   */
  private[akka] def from(cfg: Config): MessageDispatcher = configuratorFrom(cfg).dispatcher()

  private[akka] def isBalancingDispatcher(id: String): Boolean = settings.config.hasPath(id) && config(id).getString("type") == "BalancingDispatcher"

  /*
   * Creates a MessageDispatcherConfigurator from a Config.
   *
   * The Config must also contain a `id` property, which is the identifier of the dispatcher.
   *
   * Throws: IllegalArgumentException if the value of "type" is not valid
   *         IllegalArgumentException if it cannot create the MessageDispatcherConfigurator
   */
  private def configuratorFrom(cfg: Config): MessageDispatcherConfigurator = {
    if (!cfg.hasPath("id")) throw new IllegalArgumentException("Missing dispatcher 'id' property in config: " + cfg.root.render)

    cfg.getString("type") match {
      case "Dispatcher"          ⇒ new DispatcherConfigurator(cfg, prerequisites)
      case "BalancingDispatcher" ⇒ new BalancingDispatcherConfigurator(cfg, prerequisites)
      case "PinnedDispatcher"    ⇒ new PinnedDispatcherConfigurator(cfg, prerequisites)
      case fqn ⇒
        val args = Seq(classOf[Config] -> cfg, classOf[DispatcherPrerequisites] -> prerequisites)
        prerequisites.dynamicAccess.createInstanceFor[MessageDispatcherConfigurator](fqn, args) match {
          case Right(configurator) ⇒ configurator
          case Left(exception) ⇒
            throw new IllegalArgumentException(
              ("Cannot instantiate MessageDispatcherConfigurator type [%s], defined in [%s], " +
                "make sure it has constructor with [com.typesafe.config.Config] and " +
                "[akka.dispatch.DispatcherPrerequisites] parameters")
                .format(fqn, cfg.getString("id")), exception)
        }
    }
  }
}

/**
 * Configurator for creating [[akka.dispatch.Dispatcher]].
 * Returns the same dispatcher instance for for each invocation
 * of the `dispatcher()` method.
 */
class DispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val instance = new Dispatcher(
    prerequisites,
    config.getString("id"),
    config.getInt("throughput"),
    Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
    mailboxType,
    configureExecutor(),
    Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS))

  /**
   * Returns the same dispatcher instance for each invocation
   */
  override def dispatcher(): MessageDispatcher = instance
}

/**
 * Configurator for creating [[akka.dispatch.BalancingDispatcher]].
 * Returns the same dispatcher instance for for each invocation
 * of the `dispatcher()` method.
 */
class BalancingDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val instance = new BalancingDispatcher(
    prerequisites,
    config.getString("id"),
    config.getInt("throughput"),
    Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
    mailboxType, configureExecutor(),
    Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS),
    config.getBoolean("attempt-teamwork"))

  /**
   * Returns the same dispatcher instance for each invocation
   */
  override def dispatcher(): MessageDispatcher = instance
}

/**
 * Configurator for creating [[akka.dispatch.PinnedDispatcher]].
 * Returns new dispatcher instance for for each invocation
 * of the `dispatcher()` method.
 */
class PinnedDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  val threadPoolConfig: ThreadPoolConfig = configureExecutor() match {
    case e: ThreadPoolExecutorConfigurator ⇒ e.threadPoolConfig
    case other ⇒
      prerequisites.eventStream.publish(
        Warning("PinnedDispatcherConfigurator",
          this.getClass,
          "PinnedDispatcher [%s] not configured to use ThreadPoolExecutor, falling back to default config.".format(
            config.getString("id"))))
      ThreadPoolConfig()
  }
  /**
   * Creates new dispatcher for each invocation.
   */
  override def dispatcher(): MessageDispatcher =
    new PinnedDispatcher(
      prerequisites, null, config.getString("id"), mailboxType,
      Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS), threadPoolConfig)

}
