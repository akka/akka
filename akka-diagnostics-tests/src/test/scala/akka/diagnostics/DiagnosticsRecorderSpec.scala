/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.diagnostics

import java.io.File
import java.lang.management.ManagementFactory
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.testkit._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import javax.management.InstanceNotFoundException
import javax.management.ObjectName
import akka.testkit.TestEvent.Mute

object DiagnosticsRecorderSpec {
  val config = """
    akka.diagnostics.recorder.enabled = off

    #//#dir
    akka.diagnostics.recorder.dir = "target/diagnostics/"
    #//#dir

    #//#thread-dumps-count
    akka.diagnostics.recorder.collect-thread-dumps-count = 5
    #//#thread-dumps-count

    #//#thread-dumps-interval
    akka.diagnostics.recorder.collect-thread-dumps-interval = 1s
    #//#thread-dumps-interval

    akka.actor.default-dispatcher {
      throwput = 10 #intended typo
    }
    """
}

class DiagnosticsRecorderSpec extends AkkaSpec(DiagnosticsRecorderSpec.config) {

  val extSystem = system.asInstanceOf[ExtendedActorSystem]

  // mute log of the ConfigChecker warning that is due to the intended typo
  def muteTypo(s: ActorSystem): Unit =
    s.eventStream.publish(Mute(EventFilter.warning(pattern = ".*throwput.*")))
  muteTypo(system)

  def startNewSystem(name: String, config: Config = ConfigFactory.empty)(verify: (ActorSystem, File) ⇒ Unit): Unit = {
    val dirName = "target/diagnostics/"
    val cfg = config.withFallback(ConfigFactory.parseString(s"""
        akka.diagnostics.recorder {
          enabled = on
          startup-report-after = 10ms
          dir = "$dirName"
        }
        """)).withFallback(system.settings.config)

    val newSys = ActorSystem(name, cfg)
    muteTypo(newSys)
    val file = new File(dirName, DiagnosticsRecorder(newSys).reportFileName)
    try {
      verify(newSys, file)
    } finally {
      shutdown(newSys)
      file.delete()
    }
  }

  def awaitConfigWritten(file: File): Unit = {
    awaitCond(file.exists, 5.seconds)
    awaitAssert {
      ConfigFactory.parseFile(file).isEmpty should be(false)
    }
  }

  "DiagnosticsRecorder" must {

    "gather startup info and configuration" in {
      val reporter = DiagnosticsRecorder(system)
      val result = reporter.gatherStartupInfo()
      val resultJson = ConfigFactory.parseString(result)
      resultJson.hasPath("akka-version") should be(true)
      resultJson.hasPath("start-time") should be(true)
      resultJson.hasPath("classpath") should be(true)
      resultJson.hasPath("system-metrics") should be(true)
      val appConf = resultJson.getConfig("configuration")
      // modified
      appConf.hasPath("akka.diagnostics.recorder.dir") should be(true)
      appConf.hasPath("akka.loglevel") should be(true)
      // not modified
      appConf.hasPath("akka.diagnostics.recorder.dispatcher") should be(false)
      appConf.hasPath("akka.daemonic") should be(false)
      // system properties
      appConf.hasPath("java.version") should be(true)

      val configWarnings = resultJson.getConfigList("configuration-warnings")
      configWarnings.size should be(1)
      val w = configWarnings.get(0)
      w.getString("checker-key") should be("typo")
      w.getString("properties") should be("akka.actor.default-dispatcher.throwput")
    }

    "extract application specific configuration" in {
      val reporter = DiagnosticsRecorder(system)
      val cfg = ConfigFactory.parseString("""
        akka.loglevel = DEBUG
        akka.actor.default-dispatcher.throughput = 10
        some-top-level-property = 17
        some-nested {
          prop1 = 5
          prop2 = abc
        }
        """)
      val reference = ConfigFactory.defaultReference(extSystem.dynamicAccess.classLoader)
      val appConf = reporter.applicationConfig(cfg.withFallback(reference), reference)

      appConf.getString("akka.loglevel") should be("DEBUG")
      appConf.getInt("akka.actor.default-dispatcher.throughput") should be(10)
      appConf.hasPath("akka.daemonic") should be(false)
      appConf.getInt("some-top-level-property") should be(17)
      appConf.getInt("some-nested.prop1") should be(5)
      appConf.getString("some-nested.prop2") should be("abc")
      appConf.hasPath("java") should be(true)
      appConf.hasPath("user.dir") should be(false) // sensitive

    }

    "exclude sensitive configuration" in {
      val reporter = DiagnosticsRecorder(system)
      val cfg = ConfigFactory.parseString("")
      val reference = ConfigFactory.defaultReference(extSystem.dynamicAccess.classLoader)
      val appConf = reporter.applicationConfig(cfg.withFallback(reference), reference)

      appConf.hasPath("user.home") should be(false)
      appConf.hasPath("user.name") should be(false)
      appConf.hasPath("user.dir") should be(false)

      val excluded = appConf.getStringList("excluded-sensitive-paths").asScala.toSet
      excluded should contain("user.home")
      excluded should contain("user.name")
      excluded should contain("user.dir")
    }

    "write to file" in {
      startNewSystem("Sys2") { (sys2, file) ⇒
        awaitConfigWritten(file)
        val info = ConfigFactory.parseFile(file)
        info.hasPath("configuration.akka") should be(true)
      }
    }

    "write readme" in {
      startNewSystem("Sys3") { (_, file) ⇒
        awaitConfigWritten(file)
        val source = scala.io.Source.fromFile(new File(file.getParent, "readme.txt"))
        val readme = try source.getLines.mkString("\n") finally source.close()
        readme should include("http://support.typesafe.com")
        readme should include(file.getName)
      }
    }

    "be possible to disable" in {
      startNewSystem("Sys4", ConfigFactory.parseString("""
        #//#disabled
        akka.diagnostics.recorder.enabled = off
        #//#disabled

        #//#jmx-disabled
        akka.diagnostics.recorder.jmx-enabled = off
        #//#jmx-disabled
        """)) { (_, file) ⇒
        Thread.sleep(1000)
        file.exists should be(false)
      }
    }

    "be able to take thread dump" in {
      val reporter = DiagnosticsRecorder(system)
      val dump = reporter.dumpThreads()
      dump should include(Thread.currentThread.getName)
      val jsonDump = ConfigFactory.parseString(dump)
      jsonDump.hasPath("timestamp") should be(true)
      jsonDump.hasPath("all-threads") should be(true)
      jsonDump.hasPath("heap-usage") should be(true)
      jsonDump.hasPath("load-average") should be(true)
      jsonDump.hasPath("gc-count") should be(true)
      jsonDump.hasPath("gc-time") should be(true)
    }

    "register mbean in jmx" in {
      var mbeanName: ObjectName = null // will be set when "Sys5" has been started
      val mbeanServer = ManagementFactory.getPlatformMBeanServer
      val cfg = ConfigFactory.parseString("akka.diagnostics.recorder.collect-thread-dumps-count = 1")
      startNewSystem("Sys5", cfg) { (sys5, file) ⇒
        awaitConfigWritten(file)

        mbeanName = DiagnosticsRecorder(sys5).mbeanName
        val mbeanInfo = mbeanServer.getMBeanInfo(mbeanName)

        mbeanInfo.getAttributes.map(_.getName).toSet should be(Set("ReportFileLocation"))
        mbeanServer.getAttribute(mbeanName, "ReportFileLocation") should be(file.getAbsolutePath)

        mbeanInfo.getOperations.map(_.getName).toSet should be(Set("collectThreadDumps"))
        mbeanServer.invoke(mbeanName, "collectThreadDumps", Array.empty, Array.empty)
        var contentLength = 0
        within(10.seconds) {
          awaitAssert {
            val source = scala.io.Source.fromFile(file)
            val content = try source.getLines.mkString("\n") finally source.close()
            content should include(Thread.currentThread().getName)

            // until all is written
            Thread.sleep(200)
            val source2 = scala.io.Source.fromFile(file)
            val content2 = try source2.getLines.mkString("\n") finally source2.close()
            content2.length should be(content.length)

            contentLength = content.length
          }
        }

        mbeanServer.invoke(mbeanName, "collectThreadDumps", Array(Integer.valueOf(1)), Array("java.lang.Integer"))
        awaitAssert {
          val source = scala.io.Source.fromFile(file)
          val content = try source.getLines.mkString("\n") finally source.close()
          content.length should be > (contentLength)
        }
      }

      // after actor system shutdown it should be unregistered
      intercept[InstanceNotFoundException] {
        mbeanServer.getMBeanInfo(mbeanName)
      }

    }

    "not fail the system when directory cannot be created" in {
      val f = new File("akka-diagnostics-tests/target", "akka-diagnostics-file")
      if (f.createNewFile())
        try {
          val sys = ActorSystem("akka-diag-file",
            ConfigFactory.parseString(s"""akka.diagnostics.recorder{dir="${f.getPath}"\nenabled=on\nstartup-report-after=0s}""")
              .withFallback(ConfigFactory.parseString(DiagnosticsRecorderSpec.config)))
          sys.shutdown()
          sys.awaitTermination()
        } finally {
          f.delete()
        }
      else fail("could not create file " + f)
    }
  }

}
