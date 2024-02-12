/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.internal.graal

import akka.actor.ExtensionId
import akka.actor.Scheduler
import akka.dispatch.MailboxType
import akka.event.LoggingFilter
import akka.routing.RouterConfig
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization
import org.graalvm.nativeimage.hosted.RuntimeReflection
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess

/**
 * Automatic configuration of Akka for native images.
 */
class AkkaFeature extends Feature with FeatureUtils {
  // Note: Scala stdlib must not be used here

  override def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit =
    try {
      val classLoader = access.getApplicationClassLoader

      // FIXME shouldn't these rather go in the config lib?
      RuntimeResourceAccess.addResource(classLoader.getUnnamedModule, "reference.conf")
      RuntimeResourceAccess.addResource(classLoader.getUnnamedModule, "application.conf")

      registerDungeonReflection(access)
      registerPluggableCoreTypes(access)
      registerActorsForReflectiveConstruction(access)
      registerLoggersForReflectiveConstruction(access)
      registerSerializers(access)
      registerExtensions(access)
      registerSchedulers(access)
      registerRouters(access)
      // FIXME configured custom dispatchers? how would we find all custom dispatcher configs used?

    } catch {
      case ex: Throwable =>
        log("[ERROR] akka-actor Graal Feature threw exception:")
        ex.printStackTrace()
        throw ex
    }

  private def registerRouters(access: Feature.BeforeAnalysisAccess): Unit = {
    registerSubclassesForReflectiveConstruction(access, "router", classOf[RouterConfig])
  }

  private def registerSchedulers(access: Feature.BeforeAnalysisAccess): Unit = {
    // scheduler is pluggable
    registerSubclassesForReflectiveConstruction(access, "scheduler", classOf[Scheduler])
  }

  private def registerPluggableCoreTypes(access: Feature.BeforeAnalysisAccess): Unit = {
    registerSubclassesForReflectiveConstruction(access, "mailbox type", classOf[MailboxType])

    // affinity pool pluggable things
    registerSubclassesForReflectiveConstruction(
      access,
      "affinity pool rejection handler factory",
      classOf[akka.dispatch.affinity.RejectionHandlerFactory])
    registerSubclassesForReflectiveConstruction(
      access,
      "affinity pool queue selector",
      classOf[akka.dispatch.affinity.QueueSelectorFactory])

    // FIXME dns plugability deprecated, remove when dropped
    registerClassForReflectiveConstruction(access, "akka.io.InetAddressDnsProvider")
    registerClassForReflectiveConstruction(access, "akka.io.dns.internal.AsyncDnsProvider")
    registerClassForReflectiveConstruction(access, "akka.io.InetAddressDnsResolver")
    registerClassForReflectiveConstruction(access, "akka.io.dns.internal.AsyncDnsResolver")
    registerClassForReflectiveConstruction(access, "akka.io.SimpleDnsManager")
    registerClassForReflectiveConstruction(access, "akka.io.dns.internal.AsyncDnsManager")
  }

  private def registerDungeonReflection(access: Feature.BeforeAnalysisAccess): Unit = {
    registerForReflectiveFieldAccess(
      access,
      "akka.actor.ActorCell",
      // FIXME: This is Scala stdlib (Seq) but ok for some reason??
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
    registerForReflectiveFieldAccess(access, "akka.actor.LightArrayRevolverScheduler$TaskHolder", Seq("task"))
  }

  private def registerExtensions(access: Feature.BeforeAnalysisAccess): Unit = {
    val extensionClass = access.findClassByName(classOf[ExtensionId[_]].getName)
    access.registerSubtypeReachabilityHandler(
      (_, subtype) =>
        if (subtype != null && !subtype.isInterface) {
          log("Automatically registering extension for reflective access: " + subtype.getName)
          RuntimeReflection.register(subtype)
          // FIXME do we need something for Java extensions?
          try {
            RuntimeReflection.register(subtype.getField("MODULE$"))
          } catch {
            case _: NoSuchFieldException => // not a scala extension
          }

        },
      extensionClass)
  }

  private def registerActorsForReflectiveConstruction(access: Feature.BeforeAnalysisAccess): Unit = {
    // To allow for reflective Props construction
    registerSubclassesForReflectiveConstruction(access, "actor", classOf[akka.actor.Actor])
  }

  private def registerSerializers(access: Feature.BeforeAnalysisAccess): Unit = {
    // pluggable through config
    registerSubclassesForReflectiveConstruction(access, "serializer", classOf[akka.serialization.Serializer])
  }

  private def registerLoggersForReflectiveConstruction(access: Feature.BeforeAnalysisAccess): Unit = {
    // loggers themselves are just actors reflectively instantiated
    registerSubclassesForReflectiveConstruction(access, "logging filter", classOf[LoggingFilter])

    // FIXME condition on akka-slf4j being available? or move into a feature in the akka-sl4fj module?
    if (false) { // settings.Loggers.contains("akka.slf4j.Slf4jLogger")
      RuntimeClassInitialization.initializeAtBuildTime("org.slf4j.LoggerFactory", "org.slf4j.impl.StaticLoggerBinder")
    }
  }

}
