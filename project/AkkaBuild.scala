/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Properties

import akka.TestExtras.GraphiteBuildEvents
import akka.TestExtras.JUnitFileReporting
import akka.TestExtras.StatsDMetrics
import akka.Unidoc.scaladocSettings
import akka.Unidoc.unidocSettings
import com.typesafe.sbt.S3Plugin.S3
import com.typesafe.sbt.S3Plugin.s3Settings
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import sbt.Keys._
import sbt._

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  // Load system properties from a file to make configuration from Jenkins easier
  loadSystemProperties("project/akka-build.properties")

  override def buildLoaders = BuildLoader.transform(Sample.buildTransformer) :: Nil

  val enableMiMa = false

  lazy val buildSettings = Seq(
    organization        := "com.typesafe.akka",
    version             := "2.4-SNAPSHOT",
    scalaVersion        := Dependencies.Versions.scalaVersion,
    crossScalaVersions  := Dependencies.Versions.crossScala
  )

  lazy val root = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Release.settings ++ unidocSettings ++
      SphinxDoc.akkaSettings ++ Dist.settings ++ s3Settings ++ scaladocSettings ++
      GraphiteBuildEvents.settings ++ Protobuf.settings ++ Unidoc.settings(Seq(samples), Seq(remoteTests)) ++ Seq(
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Dist.distExclude := Seq(actorTests.id, docs.id, samples.id, osgi.id),

      S3.host in S3.upload := "downloads.typesafe.com.s3.amazonaws.com",
      S3.progress in S3.upload := true,
      mappings in S3.upload <<= (Release.releaseDirectory, version) map { (d, v) =>
        val downloads = d / "downloads"
        val archivesPathFinder = downloads * s"*$v.zip"
        archivesPathFinder.get.map(file => (file -> ("akka/" + file.getName)))
      }
    ),
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel, cluster, clusterMetrics, slf4j, agent,
      persistence, persistenceTck, kernel, osgi, docs, contrib, samples, multiNodeTestkit)
  )

  lazy val akkaScalaNightly = Project(
    id = "akka-scala-nightly",
    base = file("akka-scala-nightly"),
    // remove dependencies that we have to build ourselves (Scala STM)
    // samples don't work with dbuild right now
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel, cluster, slf4j,
      persistence, persistenceTck, kernel, osgi, contrib, multiNodeTestkit)
  ).disablePlugins(ValidatePullRequest)

  lazy val actor = Project(
    id = "akka-actor",
    base = file("akka-actor")
  )

  lazy val testkit = Project(
    id = "akka-testkit",
    base = file("akka-testkit"),
    dependencies = Seq(actor)
  )

  lazy val typed = Project(
    id = "akka-typed-experimental",
    base = file("akka-typed"),
    dependencies = Seq(testkit % "compile;test->test")
  )

  lazy val actorTests = Project(
    id = "akka-actor-tests",
    base = file("akka-actor-tests"),
    dependencies = Seq(testkit % "compile;test->test")
  )

  lazy val benchJmh = Project(
    id = "akka-bench-jmh",
    base = file("akka-bench-jmh"),
    dependencies = Seq(actor, persistence, testkit).map(_ % "compile;compile->test")
  ).disablePlugins(ValidatePullRequest)

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

  lazy val clusterMetrics = Project(
    id = "akka-cluster-metrics",
    base = file("akka-cluster-metrics"),
    dependencies = Seq(cluster % "compile->compile;test->test;multi-jvm->multi-jvm", slf4j % "test->compile")
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

  lazy val docs = Project(
    id = "akka-docs",
    base = file("akka-docs"),
    dependencies = Seq(actor, testkit % "test->test",
      remote % "compile;test->test", cluster, clusterMetrics, slf4j, agent, camel, osgi,
      persistence % "compile;test->test", persistenceTck,
      typed % "compile;test->test")
  )

  lazy val contrib = Project(
    id = "akka-contrib",
    base = file("akka-contrib"),
    dependencies = Seq(remote, remoteTests % "test->test", cluster, persistence)
  ) configs (MultiJvm)

  lazy val samples = Project(
    id = "akka-samples",
    base = file("akka-samples"),
    settings = parentSettings ++ ActivatorDist.settings,
    // FIXME osgiDiningHakkersSampleMavenTest temporarily removed from aggregate due to #16703
    aggregate = if (!CommandLineOptions.aggregateSamples) Nil else
      Seq(sampleCamelJava, sampleCamelScala, sampleClusterJava, sampleClusterScala, sampleFsmScala,
        sampleHelloKernel, sampleMainJava, sampleMainScala, sampleMultiNodeScala,
        samplePersistenceJava, samplePersistenceScala, sampleRemoteJava, sampleRemoteScala)
  )

  lazy val sampleCamelJava = Sample.project("akka-sample-camel-java")
  lazy val sampleCamelScala = Sample.project("akka-sample-camel-scala")

  lazy val sampleClusterJava = Sample.project("akka-sample-cluster-java")
  lazy val sampleClusterScala = Sample.project("akka-sample-cluster-scala")

  lazy val sampleFsmScala = Sample.project("akka-sample-fsm-scala")

  lazy val sampleHelloKernel = Sample.project("akka-sample-hello-kernel")

  lazy val sampleMainJava = Sample.project("akka-sample-main-java")
  lazy val sampleMainScala = Sample.project("akka-sample-main-scala")

  lazy val sampleMultiNodeScala = Sample.project("akka-sample-multi-node-scala")

  lazy val samplePersistenceJava = Sample.project("akka-sample-persistence-java")
  lazy val samplePersistenceScala = Sample.project("akka-sample-persistence-scala")

  lazy val sampleRemoteJava = Sample.project("akka-sample-remote-java")
  lazy val sampleRemoteScala = Sample.project("akka-sample-remote-scala")

  lazy val osgiDiningHakkersSampleMavenTest = Project(id = "akka-sample-osgi-dining-hakkers-maven-test",
    base = file("akka-samples/akka-sample-osgi-dining-hakkers-maven-test"),
    settings = Seq(
      publishArtifact := false,
      // force publication of artifacts to local maven repo, so latest versions can be used when running maven tests
      compile in Compile <<=
        (publishM2 in actor, publishM2 in testkit, publishM2 in remote, publishM2 in cluster, publishM2 in osgi,
          publishM2 in slf4j, publishM2 in persistence, compile in Compile) map
          ((_, _, _, _, _, _, _, c) => c),
      test in Test ~= { x => {
        def executeMvnCommands(failureMessage: String, commands: String*) = {
          if ({List("sh", "-c", commands.mkString("cd akka-samples/akka-sample-osgi-dining-hakkers; mvn ", " ", "")) !} != 0)
            throw new Exception(failureMessage)
        }
        executeMvnCommands("Osgi sample Dining hakkers test failed", "clean", "install")
      }}
    )
  )

  override lazy val settings =
    super.settings ++
    buildSettings ++
    Seq(
      shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
    ) ++
    resolverSettings

  lazy val baseSettings = Defaults.defaultSettings

  lazy val parentSettings = baseSettings ++ Seq(
    publishArtifact := false
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

  lazy val defaultSettings = baseSettings ++ resolverSettings ++ TestExtras.Filter.settings ++
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
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")
  ) ++
    mavenLocalResolverSettings ++
    JUnitFileReporting.settings ++ StatsDMetrics.settings

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
