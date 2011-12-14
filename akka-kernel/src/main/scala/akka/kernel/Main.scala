/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.kernel

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import java.io.File
import java.lang.Boolean.getBoolean
import java.net.{ URL, URLClassLoader }
import java.util.jar.JarFile
import scala.collection.JavaConverters._

trait Bootable {
  def startup(system: ActorSystem): Unit
  def shutdown(system: ActorSystem): Unit
}

object Main {
  val quiet = getBoolean("akka.kernel.quiet")

  def log(s: String) = if (!quiet) println(s)

  def main(args: Array[String]) = {
    log(banner)
    log("Starting Akka...")
    log("Running Akka " + ActorSystem.Version)

    val config = ConfigFactory.load("akka.conf")
    val name = config.getString("akka.kernel.system.name")
    val system = ActorSystem(name, config)
    val classLoader = deployJars(system)

    log("Created actor system '%s'" format name)

    Thread.currentThread.setContextClassLoader(classLoader)

    val bootClasses: Seq[String] = system.settings.config.getStringList("akka.kernel.boot").asScala
    val bootables: Seq[Bootable] = bootClasses map { c ⇒ classLoader.loadClass(c).newInstance.asInstanceOf[Bootable] }

    for (bootable ← bootables) {
      log("Starting up " + bootable.getClass.getName)
      bootable.startup(system)
    }

    addShutdownHook(system, bootables)

    log("Successfully started Akka")
  }

  def deployJars(system: ActorSystem): ClassLoader = {
    if (system.settings.Home.isEmpty) {
      log("Akka home is not defined")
      System.exit(1)
      Thread.currentThread.getContextClassLoader
    } else {
      val home = system.settings.Home.get
      val deploy = new File(home, "deploy")

      if (!deploy.exists) {
        log("No deploy dir found at " + deploy)
        log("Please check that akka home is defined correctly")
        System.exit(1)
      }

      val jars = deploy.listFiles.filter(_.getName.endsWith(".jar"))

      val nestedJars = jars flatMap { jar ⇒
        val jarFile = new JarFile(jar)
        val jarEntries = jarFile.entries.asScala.toArray.filter(_.getName.endsWith(".jar"))
        jarEntries map { entry ⇒ new File("jar:file:%s!/%s" format (jarFile.getName, entry.getName)) }
      }

      val urls = (jars ++ nestedJars) map { _.toURI.toURL }

      urls foreach { url ⇒ log("Deploying " + url) }

      new URLClassLoader(urls, Thread.currentThread.getContextClassLoader)
    }
  }

  def addShutdownHook(system: ActorSystem, bootables: Seq[Bootable]): Unit = {
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run = {
        log("")
        log("Received signal to stop")
        log("Shutting down Akka...")
        for (bootable ← bootables) {
          log("Shutting down " + bootable.getClass.getName)
          bootable.shutdown(system)
        }
        system.stop()
        log("Successfully shut down Akka")
      }
    }))
  }

  def banner = """
==============================================================================

                                                   ZZ:
                                                  ZZZZ
                                                 ZZZZZZ
                                                ZZZ' ZZZ
                                       ~7      7ZZ'   ZZZ
                                      :ZZZ:   IZZ'     ZZZ
                                     ,OZZZZ.~ZZ?        ZZZ
                                    ZZZZ' 'ZZZ$          ZZZ
                           .       $ZZZ   ~ZZ$            ZZZ
                         .=Z?.   .ZZZO   ~ZZ7              OZZ
                        .ZZZZ7..:ZZZ~   7ZZZ                ZZZ~
                      .$ZZZ$Z+.ZZZZ    ZZZ:                  ZZZ$
                   .,ZZZZ?'  =ZZO=   .OZZ                     'ZZZ
                 .$ZZZZ+   .ZZZZ    IZZZ                        ZZZ$
               .ZZZZZ'   .ZZZZ'   .ZZZ$                          ?ZZZ
            .ZZZZZZ'   .OZZZ?    ?ZZZ                             'ZZZ$
        .?ZZZZZZ'    .ZZZZ?    .ZZZ?                                'ZZZO
    .+ZZZZZZ?'    .7ZZZZ'    .ZZZZ                                    :ZZZZ
 .ZZZZZZ$'     .?ZZZZZ'   .~ZZZZ                                        'ZZZZ.


                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
    =NNNNNNNNND$      NNNNN     DDDDDD:  $NNNN+     DDDDDN     NDDNNNNNNNN,
   NNNNNNNNNNNNND     NNNNN    DNNNNN    $NNNN+   8NNNNN=    :NNNNNNNNNNNNNN
  NNNNN$    DNNNNN    NNNNN  $NNNNN~     $NNNN+  NNNNNN      NNNNN,   :NNNNN+
   ?DN~      NNNNN    NNNNN MNNNNN       $NNNN+:NNNNN7        $ND      =NNNNN
            DNNNNN    NNNNNDNNNN$        $NNNNDNNNNN                  :DNNNNN
     ZNDNNNNNNNNND    NNNNNNNNNND,       $NNNNNNNNNNN           DNDNNNNNNNNNN
   NNNNNNNDDINNNNN    NNNNNNNNNNND       $NNNNNNNNNNND       ONNNNNNND8+NNNNN
 :NNNND      NNNNN    NNNNNN  DNNNN,     $NNNNNO 7NNNND     NNNNNO     :NNNNN
 DNNNN       NNNNN    NNNNN    DNNNN     $NNNN+   8NNNNN    NNNNN      $NNNNN
 DNNNNO     NNNNNN    NNNNN     NNNNN    $NNNN+    NNNNN$   NNNND,    ,NNNNND
  NNNNNNDDNNNNNNNN    NNNNN     =NNNNN   $NNNN+     DNNNN?  DNNNNNNDNNNNNNNND
   NNNNNNNNN  NNNN$   NNNNN      8NNNND  $NNNN+      NNNNN=  ,DNNNNNNND NNNNN$

==============================================================================
"""
}
