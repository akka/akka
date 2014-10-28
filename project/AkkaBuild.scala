/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import java.io.{FileInputStream, InputStreamReader}
import java.util.Properties

import akka.Formatting.docFormatSettings
import akka.MultiNode.multiJvmSettings
import akka.TestExtras.{GraphiteBuildEvents, JUnitFileReporting, StatsDMetrics}
import akka.Unidoc.{scaladocSettings, unidocSettings}
import com.typesafe.sbt.S3Plugin.{S3, s3Settings}
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{MultiJvm, extraOptions}
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.Sphinx
import com.typesafe.tools.mima.plugin.MimaKeys.{binaryIssueFilters, previousArtifact, reportBinaryIssues}
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import sbt.Keys._
import sbt._
import sbtunidoc.Plugin.UnidocKeys.unidoc

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  // Load system properties from a file to make configuration from Jenkins easier
  loadSystemProperties("project/akka-build.properties")

  val enableMiMa = false

  lazy val buildSettings = Seq(
    organization := "com.typesafe.akka",
    version      := "2.4-SNAPSHOT",
    scalaVersion := Dependencies.Versions.scalaVersion
  )

  lazy val root = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Release.settings ++ unidocSettings ++ Publish.versionSettings ++
      SphinxDoc.akkaSettings ++ Dist.settings ++ s3Settings ++ mimaSettings ++ scaladocSettings ++
      GraphiteBuildEvents.settings ++ Protobuf.settings ++ Unidoc.settings(Seq(samples), Seq(remoteTests)) ++ Seq(
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Publish.defaultPublishTo in ThisBuild <<= crossTarget / "repository",
      Dist.distExclude := Seq(actorTests.id, docs.id, samples.id, osgi.id),

      S3.host in S3.upload := "downloads.typesafe.com.s3.amazonaws.com",
      S3.progress in S3.upload := true,
      mappings in S3.upload <<= (Release.releaseDirectory, version) map { (d, v) =>
        val downloads = d / "downloads"
        val archivesPathFinder = (downloads * ("*" + v + ".zip")) +++ (downloads * ("*" + v + ".tgz"))
        archivesPathFinder.get.map(file => (file -> ("akka/" + file.getName)))
      },

      validatePullRequest <<= (unidoc in Compile, SphinxSupport.generate in Sphinx in docs) map { (_, _) => }
    ),
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel, cluster, slf4j, agent,
      persistence, persistenceTck, zeroMQ, kernel, osgi, docs, contrib, samples, multiNodeTestkit)
  )

  lazy val akkaScalaNightly = Project(
    id = "akka-scala-nightly",
    base = file("akka-scala-nightly"),
    // remove dependencies that we have to build ourselves (Scala STM, ZeroMQ Scala Bindings)
    // samples don't work with dbuild right now
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel, cluster, slf4j,
      persistence, persistenceTck, kernel, osgi, contrib, multiNodeTestkit)
  )

  lazy val actor = Project(
    id = "akka-actor",
    base = file("akka-actor")
  )

  lazy val testkit = Project(
    id = "akka-testkit",
    base = file("akka-testkit"),
    dependencies = Seq(actor)
  )

  lazy val actorTests = Project(
    id = "akka-actor-tests",
    base = file("akka-actor-tests"),
    dependencies = Seq(testkit % "compile;test->test")
  )

  lazy val remote = Project(
    id = "akka-remote",
    base = file("akka-remote"),
    dependencies = Seq(actor, actorTests % "test->test", testkit % "test->test")
  )

  lazy val multiNodeTestkit = Project(
    id = "akka-multi-node-testkit",
    base = file("akka-multi-node-testkit"),
    dependencies = Seq(remote, testkit)
  )

  lazy val remoteTests = Project(
    id = "akka-remote-tests",
    base = file("akka-remote-tests"),
    dependencies = Seq(actorTests % "test->test", multiNodeTestkit)
  ) configs (MultiJvm)

  lazy val cluster = Project(
    id = "akka-cluster",
    base = file("akka-cluster"),
    dependencies = Seq(remote, remoteTests % "test->test" , testkit % "test->test")
  ) configs (MultiJvm)

  lazy val slf4j = Project(
    id = "akka-slf4j",
    base = file("akka-slf4j"),
    dependencies = Seq(actor, testkit % "test->test")
  )

  lazy val agent = Project(
    id = "akka-agent",
    base = file("akka-agent"),
    dependencies = Seq(actor, testkit % "test->test")
  )

  lazy val persistence = Project(
    id = "akka-persistence-experimental",
    base = file("akka-persistence"),
    dependencies = Seq(actor, remote % "test->test", testkit % "test->test")
  )

  lazy val persistenceTck = Project(
    id = "akka-persistence-experimental-tck",
    base = file("akka-persistence-tck"),
    dependencies = Seq(persistence % "compile;test->test", testkit % "compile;test->test")
  )

  lazy val zeroMQ = Project(
    id = "akka-zeromq",
    base = file("akka-zeromq"),
    dependencies = Seq(actor, testkit % "test;test->test")
  )

  lazy val kernel = Project(
    id = "akka-kernel",
    base = file("akka-kernel"),
    dependencies = Seq(actor, testkit % "test->test")
  )

  lazy val camel = Project(
    id = "akka-camel",
    base = file("akka-camel"),
    dependencies = Seq(actor, slf4j, testkit % "test->test")
  )

  lazy val osgi = Project(
    id = "akka-osgi",
    base = file("akka-osgi"),
    dependencies = Seq(actor)
  )

  lazy val samples = Project(
    id = "akka-samples",
    base = file("akka-samples"),
    settings = parentSettings ++ ActivatorDist.settings,
    aggregate = Seq(camelSampleJava, camelSampleScala, mainSampleJava, mainSampleScala,
          remoteSampleJava, remoteSampleScala, clusterSampleJava, clusterSampleScala,
          fsmSampleScala, persistenceSampleJava, persistenceSampleScala,
          multiNodeSampleScala, helloKernelSample, osgiDiningHakkersSample)
  )

  lazy val camelSampleJava = Project(
    id = "akka-sample-camel-java",
    base = file("akka-samples/akka-sample-camel-java"),
    dependencies = Seq(actor, camel),
    settings = sampleSettings ++ Seq(libraryDependencies ++= Dependencies.camelSample)
  )

  lazy val camelSampleScala = Project(
    id = "akka-sample-camel-scala",
    base = file("akka-samples/akka-sample-camel-scala"),
    dependencies = Seq(actor, camel),
    settings = sampleSettings ++ Seq(libraryDependencies ++= Dependencies.camelSample)
  )

  lazy val fsmSampleScala = Project(
    id = "akka-sample-fsm-scala",
    base = file("akka-samples/akka-sample-fsm-scala"),
    dependencies = Seq(actor),
    settings = sampleSettings
  )

  lazy val mainSampleJava = Project(
    id = "akka-sample-main-java",
    base = file("akka-samples/akka-sample-main-java"),
    dependencies = Seq(actor),
    settings = sampleSettings
  )

  lazy val mainSampleScala = Project(
    id = "akka-sample-main-scala",
    base = file("akka-samples/akka-sample-main-scala"),
    dependencies = Seq(actor),
    settings = sampleSettings
  )

  lazy val helloKernelSample = Project(
    id = "akka-sample-hello-kernel",
    base = file("akka-samples/akka-sample-hello-kernel"),
    dependencies = Seq(kernel),
    settings = sampleSettings
  )

  lazy val remoteSampleJava = Project(
    id = "akka-sample-remote-java",
    base = file("akka-samples/akka-sample-remote-java"),
    dependencies = Seq(actor, remote),
    settings = sampleSettings
  )

  lazy val remoteSampleScala = Project(
    id = "akka-sample-remote-scala",
    base = file("akka-samples/akka-sample-remote-scala"),
    dependencies = Seq(actor, remote),
    settings = sampleSettings
  )

  lazy val persistenceSampleJava = Project(
    id = "akka-sample-persistence-java",
    base = file("akka-samples/akka-sample-persistence-java"),
    dependencies = Seq(actor, persistence),
    settings = sampleSettings
  )

  lazy val persistenceSampleScala = Project(
    id = "akka-sample-persistence-scala",
    base = file("akka-samples/akka-sample-persistence-scala"),
    dependencies = Seq(actor, persistence),
    settings = sampleSettings
  )

  lazy val docs = Project(
    id = "akka-docs",
    base = file("akka-docs"),
    dependencies = Seq(actor, testkit % "test->test",
      remote % "compile;test->test", cluster, slf4j, agent, zeroMQ, camel, osgi,
      persistence % "compile;test->test", persistenceTck)
  )

  lazy val contrib = Project(
    id = "akka-contrib",
    base = file("akka-contrib"),
    dependencies = Seq(remote, remoteTests % "test->test", cluster, persistence)
  ) configs (MultiJvm)

  lazy val clusterSampleJava = Project(
    id = "akka-sample-cluster-java",
    base = file("akka-samples/akka-sample-cluster-java"),
    dependencies = Seq(cluster, contrib, remoteTests % "test", testkit % "test"),
    settings = sampleSettings ++ Seq(
      libraryDependencies ++= Dependencies.clusterSample,
      javaOptions in run ++= Seq(
        "-Djava.library.path=./sigar",
        "-Xms128m", "-Xmx1024m"),
      Keys.fork in run := true,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      }
    ) ++ multiJvmSettings
  ) configs (MultiJvm)

  lazy val clusterSampleScala = Project(
    id = "akka-sample-cluster-scala",
    base = file("akka-samples/akka-sample-cluster-scala"),
    dependencies = Seq(cluster, contrib, remoteTests % "test", testkit % "test"),
    settings = sampleSettings ++ Seq(
      libraryDependencies ++= Dependencies.clusterSample,
      javaOptions in run ++= Seq(
        "-Djava.library.path=./sigar",
        "-Xms128m", "-Xmx1024m"),
      Keys.fork in run := true,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      }
    ) ++ multiJvmSettings
  ) configs (MultiJvm)

  lazy val multiNodeSampleScala = Project(
    id = "akka-sample-multi-node-scala",
    base = file("akka-samples/akka-sample-multi-node-scala"),
    dependencies = Seq(multiNodeTestkit % "test", testkit % "test"),
    settings = sampleSettings ++ experimentalSettings ++ Seq(
      libraryDependencies ++= Dependencies.multiNodeSample,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      }
    ) ++ multiJvmSettings
  ) configs (MultiJvm)

  lazy val osgiDiningHakkersSample = Project(id = "akka-sample-osgi-dining-hakkers",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers"),
    settings = parentSettings ++ osgiSampleSettings
  ) aggregate(osgiDiningHakkersSampleApi, osgiDiningHakkersSampleCommand, osgiDiningHakkersSampleCore,
      osgiDiningHakkersSampleIntegrationTest, uncommons)

  lazy val osgiDiningHakkersSampleApi = Project(id = "akka-sample-osgi-dining-hakkers-api",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers/api"),
    settings = sampleSettings ++ osgiSampleSettings ++ OSGi.osgiDiningHakkersSampleApi
  )dependsOn(actor)

  lazy val osgiDiningHakkersSampleCommand = Project(id = "akka-sample-osgi-dining-hakkers-command",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers/command"),
    settings = sampleSettings ++ osgiSampleSettings ++ OSGi.osgiDiningHakkersSampleCommand ++ Seq(
      libraryDependencies ++= Dependencies.osgiDiningHakkersSampleCommand
    )
  ) dependsOn (osgiDiningHakkersSampleApi, actor)

  lazy val osgiDiningHakkersSampleCore = Project(id = "akka-sample-osgi-dining-hakkers-core",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers/core"),
    settings = sampleSettings ++ osgiSampleSettings ++ OSGi.osgiDiningHakkersSampleCore ++ Seq(
      libraryDependencies ++= Dependencies.osgiDiningHakkersSampleCore
    )
  ) dependsOn (osgiDiningHakkersSampleApi, actor, remote, cluster, persistence, osgi)

  lazy val osgiDiningHakkersSampleTest = Project(id = "akka-sample-osgi-dining-hakkers-test",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers/integration-test"),
    settings = sampleSettings ++ osgiSampleSettings ++ OSGi.osgiDiningHakkersSampleCore ++ Seq(
      libraryDependencies ++= Dependencies.osgiDiningHakkersSampleTest
    )
  ) dependsOn (osgiDiningHakkersSampleCommand, osgiDiningHakkersSampleCore, testkit )

  //TODO to remove it as soon as the uncommons gets OSGified, see ticket #2990
  lazy val uncommons = Project(id = "akka-sample-osgi-dining-hakkers-uncommons",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers/uncommons"),
    settings = sampleSettings ++ osgiSampleSettings ++ OSGi.osgiDiningHakkersSampleUncommons ++ Seq(
      libraryDependencies ++= Dependencies.uncommons,
      // allow publishLocal/publishM2 to overwrite
      isSnapshot := true,
      version := "1.2.0"
    )
  )

  def executeMvnCommands(failureMessage: String, commands: String*) = {
    if ({List("sh", "-c", commands.mkString("cd akka-samples/akka-sample-osgi-dining-hakkers; mvn ", " ", "")) !} != 0)
      throw new Exception(failureMessage)
  }

  lazy val osgiDiningHakkersSampleIntegrationTest = Project(id = "akka-sample-osgi-dining-hakkers-integration",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers-integration"),
    settings = sampleSettings ++ osgiSampleSettings ++ (
      if (System.getProperty("akka.osgi.sample.test", "true").toBoolean) Seq(
        test in Test ~= { x => {
          executeMvnCommands("Osgi sample Dining hakkers test failed", "clean", "install")
        }},
        // force publication of artifacts to local maven repo
        compile in Compile <<=
          (publishM2 in actor, publishM2 in testkit, publishM2 in remote, publishM2 in cluster, publishM2 in osgi,
              publishM2 in slf4j, publishM2 in persistence, compile in Compile) map
            ((_, _, _, _, _, _, _, c) => c))
      else Seq.empty
      )
  ) dependsOn(osgiDiningHakkersSampleApi, osgiDiningHakkersSampleCommand, osgiDiningHakkersSampleCore, uncommons)

  lazy val osgiSampleSettings: Seq[Setting[_]] = Seq(target :=  baseDirectory.value / "target-sbt")

  // Settings

  override lazy val settings =
    super.settings ++
    buildSettings ++
    Seq(
      shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
    ) ++
    resolverSettings

  lazy val baseSettings = Defaults.defaultSettings ++ Publish.settings

  lazy val parentSettings = baseSettings ++ Seq(
    publishArtifact := false,
    reportBinaryIssues := () // disable bin comp check
  )

  lazy val sampleSettings = defaultSettings ++ docFormatSettings ++ Seq(
    publishArtifact := false,
    reportBinaryIssues := () // disable bin comp check
  )

  lazy val experimentalSettings = Seq(
    description := """|This module of Akka is marked as
                      |experimental, which means that it is in early
                      |access mode, which also means that it is not covered
                      |by commercial support. An experimental module doesn't
                      |have to obey the rule of staying binary compatible
                      |between minor releases. Breaking API changes may be
                      |introduced in minor releases without notice as we
                      |refine and simplify based on your feedback. An
                      |experimental module may be dropped in major releases
                      |without prior deprecation.
                      |""".stripMargin
  )

  val (mavenLocalResolver, mavenLocalResolverSettings) =
    System.getProperty("akka.build.M2Dir") match {
      case null => (Resolver.mavenLocal, Seq.empty)
      case path =>
        // Maven resolver settings
        val resolver = Resolver.file("user-publish-m2-local", new File(path))
        (resolver, Seq(
          otherResolvers := resolver:: publishTo.value.toList,
          publishM2Configuration := Classpaths.publishConfig(packagedArtifacts.value, None, resolverName = resolver.name, checksums = checksums.in(publishM2).value, logging = ivyLoggingLevel.value)
        ))
    }

  lazy val resolverSettings = {
    // should we be allowed to use artifacts published to the local maven repository
    if(System.getProperty("akka.build.useLocalMavenResolver", "false").toBoolean)
      Seq(resolvers += mavenLocalResolver)
    else Seq.empty
  } ++ {
    // should we be allowed to use artifacts from sonatype snapshots
    if(System.getProperty("akka.build.useSnapshotSonatypeResolver", "false").toBoolean)
      Seq(resolvers += Resolver.sonatypeRepo("snapshots"))
    else Seq.empty
  } ++ Seq(
    pomIncludeRepository := (_ => false) // do not leak internal repositories during staging
  )

  lazy val defaultSettings = baseSettings ++ mimaSettings ++ resolverSettings ++ TestExtras.Filter.settings ++
    Protobuf.settings ++ Seq(
    // compile options
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation"),
    javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.6"),
    incOptions := incOptions.value.withNameHashing(true),

    crossVersion := CrossVersion.binary,

    ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet,

    licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    homepage := Some(url("http://akka.io/")),

    initialCommands :=
      """|import language.postfixOps
         |import akka.actor._
         |import ActorDSL._
         |import scala.concurrent._
         |import com.typesafe.config.ConfigFactory
         |import scala.concurrent.duration._
         |import akka.util.Timeout
         |var config = ConfigFactory.parseString("akka.stdout-loglevel=INFO,akka.loglevel=DEBUG,pinned{type=PinnedDispatcher,executor=thread-pool-executor,throughput=1000}")
         |var remoteConfig = ConfigFactory.parseString("akka.remote.netty{port=0,use-dispatcher-for-io=akka.actor.default-dispatcher,execution-pool-size=0},akka.actor.provider=akka.remote.RemoteActorRefProvider").withFallback(config)
         |var system: ActorSystem = null
         |implicit def _system = system
         |def startSystem(remoting: Boolean = false) { system = ActorSystem("repl", if(remoting) remoteConfig else config); println("don’t forget to system.terminate()!") }
         |implicit def ec = system.dispatcher
         |implicit val timeout = Timeout(5 seconds)
         |""".stripMargin,

    /**
     * Test settings
     */

    parallelExecution in Test := System.getProperty("akka.parallelExecution", "false").toBoolean,
    logBuffered in Test := System.getProperty("akka.logBufferedTests", "false").toBoolean,

    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),

    // don't save test output to a file
    testListeners in (Test, test) := Seq(TestLogger(streams.value.log, {_ => streams.value.log }, logBuffered.value)),

    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -a Show stack traces and exception class name for AssertionErrors.
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),

    validatePullRequestTask,
    validatePullRequest <<= validatePullRequest.dependsOn(test in Test),
    // add reportBinaryIssues to validatePullRequest on minor version maintenance branch
    validatePullRequest <<= validatePullRequest.dependsOn(reportBinaryIssues)
  ) ++
    mavenLocalResolverSettings ++
    JUnitFileReporting.settings ++ StatsDMetrics.settings

  val validatePullRequest = TaskKey[Unit]("validate-pull-request", "Additional tasks for pull request validation")
  // the tasks that to run for validation is defined in defaultSettings
  val validatePullRequestTask = validatePullRequest := ()

  lazy val mimaIgnoredProblems = {
    import com.typesafe.tools.mima.core._
    Seq(
      // add filters here, see release-2.2 branch
     )
  }

  lazy val mimaSettings = mimaDefaultSettings ++ Seq(
    // MiMa
    previousArtifact := None,
    binaryIssueFilters ++= mimaIgnoredProblems
  )

  def akkaPreviousArtifact(id: String): Def.Initialize[Option[sbt.ModuleID]] = Def.setting {
    if (enableMiMa) {
      // Note: This is a little gross because we don't have a 2.3.0 release on Scala 2.11.x
      // This should be expanded if there are more deviations.
      val version: String =
        scalaBinaryVersion.value match {
          case "2.11" => "2.3.2"
          case _ =>      "2.3.0"
        }
      val fullId = crossVersion.value match {
        case _ : CrossVersion.Binary => id + "_" + scalaBinaryVersion.value
        case _ : CrossVersion.Full => id + "_" + scalaVersion.value
        case CrossVersion.Disabled => id
      }
      Some(organization.value % fullId % version) // the artifact to compare binary compatibility with
    }
    else None
  }

  def loadSystemProperties(fileName: String): Unit = {
    import scala.collection.JavaConverters._
    val file = new File(fileName)
    if (file.exists()) {
      println("Loading system properties from file `" + fileName + "`")
      val in = new InputStreamReader(new FileInputStream(file), "UTF-8")
      val props = new Properties
      props.load(in)
      in.close()
      sys.props ++ props.asScala
    }
  }

}
