/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.internal.graal

import akka.actor.ActorSystem.Settings
import akka.actor.setup.ActorSystemSetup
import akka.serialization.Serialization
import akka.util.ccompat.JavaConverters._
import com.typesafe.config.ConfigFactory
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization
import org.graalvm.nativeimage.hosted.RuntimeReflection
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess

import scala.util.control.NonFatal

/**
 * Automatic configuration of Akka for native images.
 */
class AkkaFeature extends Feature {

  override def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit =
    try {
      val classLoader = access.getApplicationClassLoader

      // FIXME shouldn't these rather go in the config lib?
      RuntimeResourceAccess.addResource(classLoader.getUnnamedModule, "reference.conf")
      RuntimeResourceAccess.addResource(classLoader.getUnnamedModule, "application.conf")

      val config = ConfigFactory.load(classLoader)
      // FIXME this might just be the ExtensionId though is that enough?
      config
        .getStringList("akka.library-extensions")
        .asScala
        .foreach(libraryExtension => registerForReflectiveInstantiation(access, libraryExtension))
      config
        .getStringList("akka.extensions")
        .asScala
        .foreach(extension => registerForReflectiveInstantiation(access, extension))

      // serializers
      new Serialization.Settings(config).Serializers.valuesIterator.foreach(serializerName =>
        registerForReflectiveInstantiation(access, serializerName))

      val settings = new Settings(access.getApplicationClassLoader, config, "DummyName", ActorSystemSetup.empty)

      registerForReflectiveInstantiation(access, settings.ProviderClass)
      registerForReflectiveInstantiation(access, settings.SchedulerClass)
      registerForReflectiveInstantiation(access, settings.SupervisorStrategyClass)

      settings.Loggers.foreach(logger => registerForReflectiveInstantiation(access, logger))
      if (settings.Loggers.contains("akka.slf4j.Slf4jLogger")) {
        RuntimeClassInitialization.initializeAtBuildTime("org.slf4j.LoggerFactory", "org.slf4j.impl.StaticLoggerBinder")
      }
      registerForReflectiveInstantiation(access, settings.LoggingFilter)

      // executor/dispatcher stuff

      // affinity-pool-executor
      // FIXME optimally we'd do it only if used
      registerForReflectiveInstantiation(
        access,
        config.getString("akka.actor.default-dispatcher.affinity-pool-executor.rejection-handler"))
      registerForReflectiveInstantiation(
        access,
        config.getString("akka.actor.default-dispatcher.affinity-pool-executor.queue-selector"))

      // FIXME optimally we would pick only mailbox types used? (you can also select at runtime though?)
      val mailboxTypeKeys = Seq(
        "akka.actor.default-mailbox.mailbox-type",
        "akka.actor.mailbox.unbounded-queue-based.mailbox-type",
        "akka.actor.mailbox.bounded-queue-based.mailbox-type",
        "akka.actor.mailbox.unbounded-deque-based.mailbox-type",
        "akka.actor.mailbox.bounded-deque-based.mailbox-type",
        "akka.actor.mailbox.unbounded-control-aware-queue-based.mailbox-type",
        "akka.actor.mailbox.bounded-control-aware-queue-based.mailbox-type",
        "akka.actor.mailbox.logger-queue.mailbox-type")
      mailboxTypeKeys.foreach(key => registerForReflectiveInstantiation(access, config.getString(key)))

      // FIXME classic router types (optimally only if used)
      // FIXME configured custom dispatchers? how would we find all custom dispatcher configs used?

      // FIXME dispatchers used by extensions, only used if extension is, can we pick up usage from graal and opt in somehow?
      //       actually - might not be needed mostly known upfront and mapping is in code?
      // FIXME a ton of props via reflection, should we replace those with factory construction?

      // dungeon stuff
      // FIXME do we need these? (taken from the projection sample project)
      registerForReflectiveFieldAccess(
        access,
        "akka.actor.ActorCell",
        Seq(
          "akka$actor$dungeon$Children$$_childrenRefsDoNotCallMeDirectly",
          "akka$actor$dungeon$Children$$_functionRefsDoNotCallMeDirectly",
          "akka$actor$dungeon$Children$$_nextNameDoNotCallMeDirectly",
          "akka$actor$dungeon$Dispatch$$_mailboxDoNotCallMeDirectly"))

      registerForReflectiveFieldAccess(access, "akka.dispatch.Dispatcher", Seq("executorServiceDelegate"))

      registerForReflectiveFieldAccess(
        access,
        "akka.dispatch.Mailbox",
        Seq("_statusDoNotCallMeDirectly", "_systemQueueDoNotCallMeDirectly"))

      registerForReflectiveFieldAccess(
        access,
        "akka.dispatch.MessageDispatcher",
        Seq("_inhabitantsDoNotCallMeDirectly", "_shutdownScheduleDoNotCallMeDirectly"))

    } catch {
      case NonFatal(ex) =>
        println("[ERROR] akka-actor Graal Feature threw exception:")
        ex.printStackTrace()
    }

  // FIXME probably break these out into something re-usable for other modules
  private def registerForReflectiveInstantiation(access: Feature.FeatureAccess, className: String): Unit = {
    val clazz = access.findClassByName(className)
    if (clazz ne null) {
      RuntimeReflection.register(clazz)
      RuntimeReflection.register(clazz.getDeclaredConstructors: _*)
    } else {
      throw new IllegalArgumentException(s"Class not found [$className]")
    }
  }

  private def registerForReflectiveFieldAccess(
      access: Feature.FeatureAccess,
      className: String,
      fieldNames: Seq[String]): Unit = {
    val clazz = access.findClassByName(className)
    if (clazz ne null) {
      RuntimeReflection.register(clazz)
      fieldNames.foreach(fieldName => RuntimeReflection.register(clazz.getDeclaredField(fieldName)))
    } else {
      throw new IllegalArgumentException(s"Class not found [$className]")
    }
  }

}
