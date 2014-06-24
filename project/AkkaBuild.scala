/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{ MultiJvm, extraOptions, jvmOptions, scalatestOptions, multiNodeExecuteTests, multiNodeJavaName, multiNodeHostsFileName, multiNodeTargetDirName, multiTestOptions }
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.osgi.SbtOsgi.{ OsgiKeys, defaultOsgiSettings }
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
import com.typesafe.tools.mima.plugin.MimaKeys.reportBinaryIssues
import com.typesafe.tools.mima.plugin.MimaKeys.binaryIssueFilters
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.SbtSite.SiteKeys.packageSite
import com.typesafe.sbt.site.SphinxSupport
import pl.project13.scala.sbt.SbtJmh._
import com.typesafe.sbt.site.SphinxSupport.{ enableOutput, generatePdf, generatedPdf, generateEpub, generatedEpub, sphinxInputs, sphinxPackages, Sphinx }
import com.typesafe.sbt.preprocess.Preprocess.{ preprocess, preprocessExts, preprocessVars, simplePreprocess }
import java.lang.Boolean.getBoolean
import java.io.{InputStreamReader, FileInputStream, File}
import java.util.Properties
import annotation.tailrec
import Unidoc.{ JavaDoc, javadocSettings, junidocSources, sunidoc, unidocExclude }
import TestExtras. { JUnitFileReporting, StatsDMetrics }
import com.typesafe.sbt.S3Plugin.{ S3, s3Settings }

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  // Load system properties from a file to make configuration from Jenkins easier
  loadSystemProperties("project/akka-build.properties")

  val enableMiMa = true

  val requestedScalaVersion = System.getProperty("akka.scalaVersion", "2.10.4")
  val Seq(scalaEpoch, scalaMajor) = """(\d+)\.(\d+)\..*""".r.unapplySeq(requestedScalaVersion).get.map(_.toInt)
  val streamAndHttpVersion = "0.3-SNAPSHOT"

  lazy val buildSettings = Seq(
    organization := "com.typesafe.akka",
    version      := "2.3-SNAPSHOT",
    scalaVersion := requestedScalaVersion,
    scalaBinaryVersion := System.getProperty("akka.scalaBinaryVersion", if (scalaVersion.value contains "-") scalaVersion.value else scalaBinaryVersion.value)
  )

  lazy val akka = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Release.settings ++ Unidoc.settings ++ Publish.versionSettings ++
      SphinxSupport.settings ++ Dist.settings ++ s3Settings ++ mimaSettings ++ unidocScaladocSettings ++
      StatsDMetrics.settings ++
      Protobuf.settings ++ inConfig(JavaDoc)(Defaults.configSettings) ++ Seq(
      testMailbox in GlobalScope := System.getProperty("akka.testMailbox", "false").toBoolean,
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Publish.defaultPublishTo in ThisBuild <<= crossTarget / "repository",
      unidocExclude := Seq(samples.id, remoteTests.id, parsing.id),
      sources in JavaDoc <<= junidocSources,
      javacOptions in JavaDoc := Seq(),
      artifactName in packageDoc in JavaDoc := ((sv, mod, art) => "" + mod.name + "_" + sv.binary + "-" + mod.revision + "-javadoc.jar"),
      packageDoc in Compile <<= packageDoc in JavaDoc,
      Dist.distExclude := Seq(actorTests.id, docs.id, samples.id, osgi.id),
      // generate online version of docs
      sphinxInputs in Sphinx <<= sphinxInputs in Sphinx in LocalProject(docs.id) map { inputs => inputs.copy(tags = inputs.tags :+ "online") },
      // don't regenerate the pdf, just reuse the akka-docs version
      generatedPdf in Sphinx <<= generatedPdf in Sphinx in LocalProject(docs.id) map identity,
      generatedEpub in Sphinx <<= generatedEpub in Sphinx in LocalProject(docs.id) map identity,

      S3.host in S3.upload := "downloads.typesafe.com.s3.amazonaws.com",
      S3.progress in S3.upload := true,
      mappings in S3.upload <<= (Release.releaseDirectory, version) map { (d, v) =>
        val downloads = d / "downloads"
        val archivesPathFinder = (downloads * ("*" + v + ".zip")) +++ (downloads * ("*" + v + ".tgz"))
        archivesPathFinder.get.map(file => (file -> ("akka/" + file.getName)))
      },
      validatePullRequest <<= (SphinxSupport.generate in Sphinx in docsDev, test in Test in stream, test in Test in httpCore,
        test in Test in docsDev) map { (_, _, _, _) => }
    ),
    aggregate = Seq(actor, testkit, actorTests, dataflow, remote, remoteTests, camel, cluster, slf4j, agent, transactor,
      persistence, mailboxes, zeroMQ, kernel, osgi, docs, contrib, samples, multiNodeTestkit, stream, parsing, httpCore,
      docsDev)
  )

  lazy val akkaScalaNightly = Project(
    id = "akka-scala-nightly",
    base = file("akka-scala-nightly"),
    // remove dependencies that we have to build ourselves (Scala STM, ZeroMQ Scala Bindings)
    // samples and dataflow don't work with dbuild right now
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel, cluster, slf4j,
      persistence, stream, mailboxes, kernel, osgi, contrib, multiNodeTestkit)
  )

  // this detached pseudo-project is used for running the tests against a different Scala version than the one used for compilation
  // usage:
  //   all-tests/test (or test-only)
  // customizing (on the SBT command line):
  //   set scalaVersion in allTests := "2.11.0"
  lazy val multiJvmProjects = Seq(remoteTests, cluster)
  lazy val allTests = Project(
    id = "all-tests",
    base = file("all-tests"),
    dependencies = (
      ((akka.aggregate: Seq[ProjectReference]) map (_ % "test->test")) ++
      (multiJvmProjects map (_ % "multi-jvm->multi-jvm"))
    ),
    settings = defaultSettings ++ multiJvmSettings ++ Seq(
      scalaVersion := requestedScalaVersion,
      publishArtifact := false,
      definedTests in Test := Nil
    ) ++ (
      (akka.aggregate: Seq[ProjectReference])
        filterNot {
          case LocalProject(name) => name contains "slf4j"
          case _                  => false
        } map {
          pr => definedTests in Test <++= definedTests in (pr, Test)
        }
    ) ++ (
      multiJvmProjects map {
        pr => definedTests in MultiJvm <++= definedTests in (pr, MultiJvm)
      }
    ) ++ Seq(
      scalatestOptions in MultiJvm := defaultMultiJvmScalatestOptions
    )
  ) configs (MultiJvm)

  lazy val atmos = Project(
    id = "atmos",
    base = file("atmos"),
    dependencies = Seq(allTests % "test->test;multi-jvm->multi-jvm"),
    settings = defaultSettings ++ multiJvmSettings ++ Seq(
      fork in Test := true,
      definedTests in Test <<= definedTests in allTests in Test,
      libraryDependencies += "org.aspectj" % "aspectjweaver" % "1.7.2",
      libraryDependencies += "com.typesafe.atmos" % "trace-akka-2.2.0-RC1_2.10" % "1.2.0-M5" excludeAll(ExclusionRule(organization = "com.typesafe.akka")),
      resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
      javaOptions in Test <++= (update) map { (u) =>
        val f = u.matching(configurationFilter("compile") && moduleFilter(name = "aspectjweaver")).head
        Seq("-javaagent:" + f.getAbsolutePath, "-Dorg.aspectj.tracing.factory=default")
      },
      definedTests in MultiJvm <++= definedTests in (allTests, MultiJvm),
      scalatestOptions in MultiJvm <<= scalatestOptions in (allTests, MultiJvm),
      multiTestOptions in MultiJvm <<= (multiTestOptions in MultiJvm, javaOptions in Test) map { (multiOptions, testOptions) =>
        multiOptions.copy(jvm = multiOptions.jvm ++ testOptions)
      }
    )
  ) configs (MultiJvm)

  lazy val actor = Project(
    id = "akka-actor",
    base = file("akka-actor"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.actor ++ Seq(
      // to fix scaladoc generation
      fullClasspath in doc in Compile <<= fullClasspath in Compile,
      libraryDependencies ++= Dependencies.actor,
      previousArtifact := akkaPreviousArtifact("akka-actor")
    )
  )

  val cpsPlugin = Seq(
    libraryDependencies <++= scalaVersion { v =>
      if (v.startsWith("2.10.")) Seq(compilerPlugin("org.scala-lang.plugins" % "continuations" % v))
      else Seq(
        compilerPlugin("org.scala-lang.plugins" %% "scala-continuations-plugin" % Dependencies.Versions.scalaContinuationsVersion cross CrossVersion.full),
        "org.scala-lang.plugins" %% "scala-continuations-library" % Dependencies.Versions.scalaContinuationsVersion)
    },
    scalacOptions += "-P:continuations:enable"
  )

  lazy val dataflow = Project(
    id = "akka-dataflow",
    base = file("akka-dataflow"),
    dependencies = Seq(testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettingsNoVerificationOfDiagrams  ++ OSGi.dataflow ++ cpsPlugin ++ Seq(
      previousArtifact := akkaPreviousArtifact("akka-dataflow")
    )
  )

  lazy val testkit = Project(
    id = "akka-testkit",
    base = file("akka-testkit"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.testkit ++ Seq(
      libraryDependencies ++= Dependencies.testkit,
      initialCommands += "import akka.testkit._",
      previousArtifact := akkaPreviousArtifact("akka-testkit")
    )
  )

  lazy val benchJmh = Project(
    id = "akka-bench-jmh",
    base = file("akka-bench-jmh"),
    dependencies = Seq(actor, stream, persistence, testkit).map(_ % "compile;compile->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.testkit
    ) ++ settings ++ jmhSettings
  )

  lazy val actorTests = Project(
    id = "akka-actor-tests",
    base = file("akka-actor-tests"),
    dependencies = Seq(testkit % "compile;test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ Seq(
      publishArtifact in Compile := false,
      libraryDependencies ++= Dependencies.actorTests
    )
  )

  lazy val remote = Project(
    id = "akka-remote",
    base = file("akka-remote"),
    dependencies = Seq(actor, actorTests % "test->test", testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.remote ++ Seq(
      libraryDependencies ++= Dependencies.remote,
      // disable parallel tests
      parallelExecution in Test := false,
      previousArtifact := akkaPreviousArtifact("akka-remote")
    )
  )

  lazy val multiNodeTestkit = Project(
    id = "akka-multi-node-testkit",
    base = file("akka-multi-node-testkit"),
    dependencies = Seq(remote, testkit),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ Seq(
      previousArtifact := akkaPreviousArtifact("akka-multi-node-testkit")
    )
  )

  lazy val remoteTests = Project(
    id = "akka-remote-tests",
    base = file("akka-remote-tests"),
    dependencies = Seq(actorTests % "test->test", multiNodeTestkit),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ multiJvmSettings ++ Seq(
      libraryDependencies ++= Dependencies.remoteTests,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := defaultMultiJvmScalatestOptions,
      publishArtifact in Compile := false,
      reportBinaryIssues := () // disable bin comp check
    )
  ) configs (MultiJvm)

  lazy val cluster = Project(
    id = "akka-cluster",
    base = file("akka-cluster"),
    dependencies = Seq(remote, remoteTests % "test->test" , testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ multiJvmSettings ++ OSGi.cluster ++ Seq(
      libraryDependencies ++= Dependencies.cluster,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := defaultMultiJvmScalatestOptions,
      previousArtifact := akkaPreviousArtifact("akka-cluster")
    )
  ) configs (MultiJvm)

  lazy val slf4j = Project(
    id = "akka-slf4j",
    base = file("akka-slf4j"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.slf4j ++ Seq(
      libraryDependencies ++= Dependencies.slf4j,
      previousArtifact := akkaPreviousArtifact("akka-slf4j")
    )
  )

  lazy val agent = Project(
    id = "akka-agent",
    base = file("akka-agent"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettingsNoVerificationOfDiagrams ++ javadocSettings ++ OSGi.agent ++ Seq(
      libraryDependencies ++= Dependencies.agent,
      previousArtifact := akkaPreviousArtifact("akka-agent")
    )
  )

  lazy val transactor = Project(
    id = "akka-transactor",
    base = file("akka-transactor"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.transactor ++ Seq(
      libraryDependencies ++= Dependencies.transactor,
      previousArtifact := akkaPreviousArtifact("akka-transactor")
    )
  )

  lazy val persistence = Project(
    id = "akka-persistence-experimental",
    base = file("akka-persistence"),
    dependencies = Seq(actor, remote % "test->test", testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ experimentalSettings ++ javadocSettings ++ OSGi.persistence ++ Seq(
      fork in Test := true,
      javaOptions in Test := defaultMultiJvmOptions,
      libraryDependencies ++= Dependencies.persistence,
      previousArtifact := akkaPreviousArtifact("akka-persistence-experimental")
    )
  )

  lazy val streamAndHttp = Project(
    id = "akka-stream-and-http-experimental",
    base = file("akka-stream-and-http"),
    settings = parentSettings ++ Release.settings ++ Unidoc.settings ++ Publish.versionSettings ++
      SphinxSupport.settings ++ Dist.settings ++ mimaSettings ++ unidocScaladocSettings ++
      StatsDMetrics.settings ++
      Protobuf.settings ++ inConfig(JavaDoc)(Defaults.configSettings) ++ Seq(
      version := streamAndHttpVersion,
      testMailbox in GlobalScope := System.getProperty("akka.testMailbox", "false").toBoolean,
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Publish.defaultPublishTo in ThisBuild <<= crossTarget / "repository",
      unidocExclude := Seq(parsing.id, docsDev.id),
      sources in JavaDoc <<= junidocSources,
      javacOptions in JavaDoc := Seq(),
      artifactName in packageDoc in JavaDoc := ((sv, mod, art) => "" + mod.name + "_" + sv.binary + "-" + mod.revision + "-javadoc.jar"),
      packageDoc in Compile <<= packageDoc in JavaDoc,
      Dist.distExclude := Seq(docsDev.id),
      // generate online version of docs
      sphinxInputs in Sphinx <<= sphinxInputs in Sphinx in LocalProject(docsDev.id) map { inputs => inputs.copy(tags = inputs.tags :+ "online") },
      // don't regenerate the pdf, just reuse the akka-docs version
      generatedPdf in Sphinx <<= generatedPdf in Sphinx in LocalProject(docsDev.id) map identity,
      generatedEpub in Sphinx <<= generatedEpub in Sphinx in LocalProject(docsDev.id) map identity,
      publishArtifact in packageSite := false
    ),
    aggregate = Seq(parsing, stream, httpCore, docsDev)
  )

  lazy val httpCore = Project(
    id = "akka-http-core-experimental",
    base = file("akka-http-core"),
    dependencies = Seq(parsing, stream % "compile;test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.httpCore ++ Seq(
      version := streamAndHttpVersion,
      libraryDependencies ++= Dependencies.httpCore,
      // FIXME include mima when akka-http-core-2.3.x is released
      //previousArtifact := akkaPreviousArtifact("akka-http-core-experimental")
      previousArtifact := None
    )
  )

  lazy val parsing = Project(
    id = "akka-parsing-experimental",
    base = file("akka-parsing"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ OSGi.parsing ++ Seq(
      version := streamAndHttpVersion,
      javacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-source", "1.6",
        "-target", "1.6",
        "-Xlint:unchecked",
        "-Xlint:deprecation"),
      scalacOptions += "-language:_",
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-reflect" % "2.10.4" % "provided",
        "org.scalamacros" %% "quasiquotes" % "2.0.0" % "compile"),
      // ScalaDoc doesn't like the macros
      sources in doc in Compile := List(),
      publishArtifact in packageDoc := false,
      addCompilerPlugin("org.scalamacros" % "paradise" % "2.0.0" cross CrossVersion.full),
      // FIXME include mima when akka-http-core-2.3.x is released
      //previousArtifact := akkaPreviousArtifact("akka-parsing-experimental")
      previousArtifact := None
    )
  )

  lazy val stream = Project(
    id = "akka-stream-experimental",
    base = file("akka-stream"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ experimentalSettings ++ javadocSettings ++ OSGi.stream ++ Seq(
      version := streamAndHttpVersion,
      libraryDependencies ++= Dependencies.stream,
      // FIXME include mima when akka-stream-experimental-2.3.x has been released
      //previousArtifact := akkaPreviousArtifact("akka-stream-experimental")
      previousArtifact := None,
      fork in Test := true
    )
  )

  val testMailbox = SettingKey[Boolean]("test-mailbox")

  lazy val mailboxes = Project(
    id = "akka-durable-mailboxes",
    base = file("akka-durable-mailboxes"),
    settings = parentSettings,
    aggregate = Seq(mailboxesCommon, fileMailbox)
  )

  lazy val mailboxesCommon = Project(
    id = "akka-mailboxes-common",
    base = file("akka-durable-mailboxes/akka-mailboxes-common"),
    dependencies = Seq(remote, testkit % "compile;test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.mailboxesCommon ++ Seq(
      libraryDependencies ++= Dependencies.mailboxes,
      previousArtifact := akkaPreviousArtifact("akka-mailboxes-common"),
      publishArtifact in Test := true
    )
  )

  lazy val fileMailbox = Project(
    id = "akka-file-mailbox",
    base = file("akka-durable-mailboxes/akka-file-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test", testkit % "test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.fileMailbox ++ Seq(
      libraryDependencies ++= Dependencies.fileMailbox,
      previousArtifact := akkaPreviousArtifact("akka-file-mailbox")
    )
  )

  lazy val zeroMQ = Project(
    id = "akka-zeromq",
    base = file("akka-zeromq"),
    dependencies = Seq(actor, testkit % "test;test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.zeroMQ ++ Seq(
      libraryDependencies ++= Dependencies.zeroMQ,
      previousArtifact := akkaPreviousArtifact("akka-zeromq")
    )
  )

  lazy val kernel = Project(
    id = "akka-kernel",
    base = file("akka-kernel"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettingsNoVerificationOfDiagrams ++ javadocSettings ++ Seq(
      libraryDependencies ++= Dependencies.kernel,
      previousArtifact := akkaPreviousArtifact("akka-kernel")
    )
  )

  lazy val camel = Project(
    id = "akka-camel",
    base = file("akka-camel"),
    dependencies = Seq(actor, slf4j, testkit % "test->test"),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.camel ++ Seq(
      libraryDependencies ++= Dependencies.camel,
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
      previousArtifact := akkaPreviousArtifact("akka-camel")
    )
  )

  lazy val osgi = Project(
    id = "akka-osgi",
    base = file("akka-osgi"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ OSGi.osgi ++ Seq(
      libraryDependencies ++= Dependencies.osgi,
      parallelExecution in Test := false,
      reportBinaryIssues := () // disable bin comp check
    )
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
    dependencies = Seq(actor, persistence, stream),
    settings = sampleSettings
  )

  lazy val clusterSampleJava = Project(
    id = "akka-sample-cluster-java",
    base = file("akka-samples/akka-sample-cluster-java"),
    dependencies = Seq(cluster, contrib, remoteTests % "test", testkit % "test"),
    settings = multiJvmSettings ++ sampleSettings ++ Seq(
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
    )
  ) configs (MultiJvm)

  lazy val clusterSampleScala = Project(
    id = "akka-sample-cluster-scala",
    base = file("akka-samples/akka-sample-cluster-scala"),
    dependencies = Seq(cluster, contrib, remoteTests % "test", testkit % "test"),
    settings = multiJvmSettings ++ sampleSettings ++ Seq(
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
    )
  ) configs (MultiJvm)

  lazy val multiNodeSampleScala = Project(
    id = "akka-sample-multi-node-scala",
    base = file("akka-samples/akka-sample-multi-node-scala"),
    dependencies = Seq(multiNodeTestkit % "test", testkit % "test"),
    settings = multiJvmSettings ++ sampleSettings ++ experimentalSettings ++ Seq(
      libraryDependencies ++= Dependencies.multiNodeSample,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      }
    )
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

  lazy val docs = Project(
    id = "akka-docs",
    base = file("akka-docs"),
    dependencies = Seq(actor, testkit % "test->test",
      remote % "compile;test->test", cluster, slf4j, agent, zeroMQ, camel, osgi, persistence),
    settings = defaultSettings ++ docFormatSettings ++ site.settings ++ site.sphinxSupport() ++ site.publishSite ++ sphinxPreprocessing ++ cpsPlugin ++ Seq(
      sourceDirectory in Sphinx <<= baseDirectory / "rst",
      watchSources <++= (sourceDirectory in Sphinx, excludeFilter in Global) map { (source, excl) =>
        source descendantsExcept ("*.rst", excl) get
      },
      sphinxPackages in Sphinx <+= baseDirectory { _ / "_sphinx" / "pygments" },
      // copy akka-contrib/docs into our rst_preprocess/contrib (and apply substitutions)
      preprocess in Sphinx <<= (preprocess in Sphinx,
                                baseDirectory in contrib,
                                target in preprocess in Sphinx,
                                cacheDirectory,
                                preprocessExts in Sphinx,
                                preprocessVars in Sphinx,
                                streams) map { (orig, src, target, cacheDir, exts, vars, s) =>
        val contribSrc = Map("contribSrc" -> "../../../akka-contrib")
        simplePreprocess(src / "docs", target / "contrib", cacheDir / "sphinx" / "preprocessed-contrib", exts, vars ++ contribSrc, s.log)
        orig
      },
      enableOutput in generatePdf in Sphinx := true,
      enableOutput in generateEpub in Sphinx := true,
      unmanagedSourceDirectories in Test <<= sourceDirectory in Sphinx apply { _ ** "code" get },
      libraryDependencies ++= Dependencies.docs,
      publishArtifact in Compile := false,
      unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test,
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
      reportBinaryIssues := () // disable bin comp check
    )
  )

  lazy val docsDev = Project(
    id = "akka-docs-dev",
    base = file("akka-docs-dev"),
    dependencies = Seq(stream % "test -> test", httpCore),
    settings = defaultSettings ++ docFormatSettings ++ site.settings ++ site.sphinxSupport() ++ site.publishSite ++ sphinxPreprocessing ++ cpsPlugin ++ Seq(
      version := streamAndHttpVersion,
      sourceDirectory in Sphinx <<= baseDirectory / "rst",
      watchSources <++= (sourceDirectory in Sphinx, excludeFilter in Global) map { (source, excl) =>
        source descendantsExcept ("*.rst", excl) get
      },
      sphinxPackages in Sphinx <+= baseDirectory { _ / "_sphinx" / "pygments" },
      enableOutput in generatePdf in Sphinx := true,
      enableOutput in generateEpub in Sphinx := true,
      unmanagedSourceDirectories in Test <<= sourceDirectory in Sphinx apply { _ ** "code" get },
      libraryDependencies ++= Dependencies.docs,
      unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test,
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
      publishArtifact := false,
      reportBinaryIssues := () // disable bin comp check
    )
  )

  lazy val contrib = Project(
    id = "akka-contrib",
    base = file("akka-contrib"),
    dependencies = Seq(remote, remoteTests % "test->test", cluster, persistence),
    settings = defaultSettings ++ formatSettings ++ scaladocSettings ++ javadocSettings ++ multiJvmSettings ++ Seq(
      libraryDependencies ++= Dependencies.contrib,
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v"),
      reportBinaryIssues := (), // disable bin comp check
      description := """|
                        |This subproject provides a home to modules contributed by external
                        |developers which may or may not move into the officially supported code
                        |base over time. A module in this subproject doesn't have to obey the rule
                        |of staying binary compatible between minor releases. Breaking API changes
                        |may be introduced in minor releases without notice as we refine and
                        |simplify based on your feedback. A module may be dropped in any release
                        |without prior deprecation. The Typesafe subscription does not cover
                        |support for these modules.
                        |""".stripMargin
    )
  ) configs (MultiJvm)

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
    publishArtifact in (Compile, packageBin) := false,
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

  val excludeTestNames = SettingKey[Seq[String]]("exclude-test-names")
  val excludeTestTags = SettingKey[Set[String]]("exclude-test-tags")
  val onlyTestTags = SettingKey[Set[String]]("only-test-tags")

  lazy val defaultMultiJvmOptions: Seq[String] = {
    import scala.collection.JavaConverters._
    // multinode.D= and multinode.X= makes it possible to pass arbitrary
    // -D or -X arguments to the forked jvm, e.g.
    // -Dmultinode.Djava.net.preferIPv4Stack=true -Dmultinode.Xmx512m -Dmultinode.XX:MaxPermSize=256M
    // -DMultiJvm.akka.cluster.Stress.nrOfNodes=15
    val MultinodeJvmArgs = "multinode\\.(D|X)(.*)".r
    val knownPrefix = Set("multnode.", "akka.", "MultiJvm.")
    val akkaProperties = System.getProperties.propertyNames.asScala.toList.collect {
      case MultinodeJvmArgs(a, b) =>
        val value = System.getProperty("multinode." + a + b)
        "-" + a + b + (if (value == "") "" else "=" + value)
      case key: String if knownPrefix.exists(pre => key.startsWith(pre)) => "-D" + key + "=" + System.getProperty(key)
    }

    "-Xmx256m" :: akkaProperties :::
      (if (getBoolean("sbt.log.noformat")) List("-Dakka.test.nocolor=true") else Nil)
  }

  // for excluding tests by name use system property: -Dakka.test.names.exclude=TimingSpec
  // not supported by multi-jvm tests
  lazy val useExcludeTestNames: Seq[String] = systemPropertyAsSeq("akka.test.names.exclude")

  // for excluding tests by tag use system property: -Dakka.test.tags.exclude=<tag name>
  // note that it will not be used if you specify -Dakka.test.tags.only
  lazy val useExcludeTestTags: Set[String] = {
    if (useOnlyTestTags.isEmpty) systemPropertyAsSeq("akka.test.tags.exclude").toSet
    else Set.empty
  }

  // for running only tests by tag use system property: -Dakka.test.tags.only=<tag name>
  lazy val useOnlyTestTags: Set[String] = systemPropertyAsSeq("akka.test.tags.only").toSet

  def executeMultiJvmTests: Boolean = {
    useOnlyTestTags.contains("long-running") || !useExcludeTestTags.contains("long-running")
  }

  def systemPropertyAsSeq(name: String): Seq[String] = {
    val prop = System.getProperty(name, "")
    if (prop.isEmpty) Seq.empty else prop.split(",").toSeq
  }

  val multiNodeEnabled = java.lang.Boolean.getBoolean("akka.test.multi-node")

  lazy val defaultMultiJvmScalatestOptions: Seq[String] = {
    val excludeTags = useExcludeTestTags.toSeq
    Seq("-C", "org.scalatest.akka.QuietReporter") ++
    (if (excludeTags.isEmpty) Seq.empty else Seq("-l", if (multiNodeEnabled) excludeTags.mkString("\"", " ", "\"") else excludeTags.mkString(" "))) ++
    (if (useOnlyTestTags.isEmpty) Seq.empty else Seq("-n", if (multiNodeEnabled) useOnlyTestTags.mkString("\"", " ", "\"") else useOnlyTestTags.mkString(" ")))
  }

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

  lazy val defaultSettings = baseSettings ++ mimaSettings ++ resolverSettings ++
    Protobuf.settings ++ Seq(
    // compile options
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation"),
    javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.6"),

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
         |def startSystem(remoting: Boolean = false) { system = ActorSystem("repl", if(remoting) remoteConfig else config); println("don’t forget to system.shutdown()!") }
         |implicit def ec = system.dispatcher
         |implicit val timeout = Timeout(5 seconds)
         |""".stripMargin,

    /**
     * Test settings
     */

    parallelExecution in Test := System.getProperty("akka.parallelExecution", "false").toBoolean,
    logBuffered in Test := System.getProperty("akka.logBufferedTests", "false").toBoolean,

    excludeTestNames := useExcludeTestNames,
    excludeTestTags := useExcludeTestTags,
    onlyTestTags := useOnlyTestTags,

    // add filters for tests excluded by name
    testOptions in Test <++= excludeTestNames map { _.map(exclude => Tests.Filter(test => !test.contains(exclude))) },

    // add arguments for tests excluded by tag
    testOptions in Test <++= excludeTestTags map { tags =>
      if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-l", tags.mkString(" ")))
    },

    // add arguments for running only tests by tag
    testOptions in Test <++= onlyTestTags map { tags =>
      if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-n", tags.mkString(" ")))
    },

    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),

    // don't save test output to a file
    testListeners in (Test, test) := Seq(TestLogger(streams.value.log, {_ => streams.value.log }, logBuffered.value)),

    validatePullRequestTask
    // add reportBinaryIssues to validatePullRequest on minor version maintenance branch
    //validatePullRequest <<= validatePullRequest.dependsOn(reportBinaryIssues)

  ) ++ mavenLocalResolverSettings ++ JUnitFileReporting.settings ++ StatsDMetrics.settings


  val validatePullRequest = TaskKey[Unit]("validate-pull-request", "Additional tasks for pull request validation")
  // the tasks that to run for validation is defined in defaultSettings
  val validatePullRequestTask = validatePullRequest := ()

  def githubUrl(v: String): String = {
    val branch = "release-2.3-dev"
    "http://github.com/akka/akka/tree/" + branch
  }

  // preprocessing settings for sphinx
  lazy val sphinxPreprocessing = inConfig(Sphinx)(Seq(
    target in preprocess <<= baseDirectory / "rst_preprocessed",
    preprocessExts := Set("rst", "py"),
    // customization of sphinx @<key>@ replacements, add to all sphinx-using projects
    // add additional replacements here
    preprocessVars <<= (scalaVersion, version) { (s, akkaV) =>
      val v = streamAndHttpVersion
      val BinVer = """(\d+\.\d+)\.\d+""".r
      Map(
        "version" -> v,
        "scalaVersion" -> s,
        "crossString" -> (s match {
            case BinVer(_) => ""
            case _         => "cross CrossVersion.full"
          }),
        "jarName" -> (s match {
            case BinVer(bv) => "akka-actor_" + bv + "-" + v + ".jar"
            case _          => "akka-actor_" + s + "-" + v + ".jar"
          }),
        "binVersion" -> (s match {
            case BinVer(bv) => bv
            case _          => s
          }),
        "sigarVersion" -> Dependencies.Compile.sigar.revision,
        "github" -> githubUrl(v)
      )
    },
    preprocess <<= (sourceDirectory, target in preprocess, cacheDirectory, preprocessExts, preprocessVars, streams) map {
      (src, target, cacheDir, exts, vars, s) => simplePreprocess(src, target, cacheDir / "sphinx" / "preprocessed", exts, vars, s.log)
    },
    sphinxInputs <<= (sphinxInputs, preprocess) map { (inputs, preprocessed) => inputs.copy(src = preprocessed) }
  )) ++ Seq(
    cleanFiles <+= target in preprocess in Sphinx
  )

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile  := formattingPreferences,
    ScalariformKeys.preferences in Test     := formattingPreferences
  )

  lazy val docFormatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile  := docFormattingPreferences,
    ScalariformKeys.preferences in Test     := docFormattingPreferences,
    ScalariformKeys.preferences in MultiJvm := docFormattingPreferences
  )

  def formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
  }

  def docFormattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
    .setPreference(RewriteArrowSymbols, false)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
  }

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ inConfig(MultiJvm)(SbtScalariform.configScalariformSettings) ++ Seq(
    jvmOptions in MultiJvm := defaultMultiJvmOptions,
    compileInputs in (MultiJvm, compile) <<= (compileInputs in (MultiJvm, compile)) dependsOn (ScalariformKeys.format in MultiJvm),
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    ScalariformKeys.preferences in MultiJvm := formattingPreferences) ++
    Option(System.getProperty("akka.test.multi-node.hostsFileName")).map(x => Seq(multiNodeHostsFileName in MultiJvm := x)).getOrElse(Seq.empty) ++
    Option(System.getProperty("akka.test.multi-node.java")).map(x => Seq(multiNodeJavaName in MultiJvm := x)).getOrElse(Seq.empty) ++
    Option(System.getProperty("akka.test.multi-node.targetDirName")).map(x => Seq(multiNodeTargetDirName in MultiJvm := x)).getOrElse(Seq.empty) ++
    ((executeMultiJvmTests, multiNodeEnabled) match {
      case (true, true) =>
        executeTests in Test <<= (executeTests in Test, multiNodeExecuteTests in MultiJvm) map {
          case (testResults, multiNodeResults)  =>
            val overall =
              if (testResults.overall.id < multiNodeResults.overall.id)
                multiNodeResults.overall
              else
                testResults.overall
            Tests.Output(overall,
              testResults.events ++ multiNodeResults.events,
              testResults.summaries ++ multiNodeResults.summaries)
        }
      case (true, false) =>
        executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
          case (testResults, multiNodeResults)  =>
            val overall =
              if (testResults.overall.id < multiNodeResults.overall.id)
                multiNodeResults.overall
              else
                testResults.overall
            Tests.Output(overall,
              testResults.events ++ multiNodeResults.events,
              testResults.summaries ++ multiNodeResults.summaries)
        }
      case (false, _) => Seq.empty
    })

  lazy val scaladocDiagramsEnabled = System.getProperty("akka.scaladoc.diagrams", "true").toBoolean
  lazy val scaladocAutoAPI = System.getProperty("akka.scaladoc.autoapi", "true").toBoolean

  def scaladocOptions(ver: String, base: File): List[String] = {
    val urlString = githubUrl(ver) + "/€{FILE_PATH}.scala"
    val opts = List("-implicits", "-doc-source-url", urlString, "-sourcepath", base.getAbsolutePath)
    if (scaladocDiagramsEnabled) "-diagrams"::opts else opts
  }

  lazy val scaladocSettings: Seq[sbt.Setting[_]] = {
    scaladocSettingsNoVerificationOfDiagrams ++
    (if (scaladocDiagramsEnabled) Seq(doc in Compile ~= scaladocVerifier) else Seq.empty)
  }

  // for projects with few (one) classes there might not be any diagrams
  lazy val scaladocSettingsNoVerificationOfDiagrams: Seq[sbt.Setting[_]] = {
    inTask(doc)(Seq(
      scalacOptions in Compile <++= (version, baseDirectory in akka) map scaladocOptions,
      autoAPIMappings := scaladocAutoAPI
    ))
  }

  lazy val unidocScaladocSettings: Seq[sbt.Setting[_]]= {
    inTask(doc)(Seq(
      scalacOptions <++= (version, baseDirectory in akka) map scaladocOptions,
      autoAPIMappings := scaladocAutoAPI
    )) ++
    (if (scaladocDiagramsEnabled) Seq(sunidoc ~= scaladocVerifier) else Seq.empty)
  }

  def scaladocVerifier(file: File): File= {
    @tailrec
    def findHTMLFileWithDiagram(dirs: Seq[File]): Boolean = {
      if (dirs.isEmpty) false
      else {
        val curr = dirs.head
        val (newDirs, files) = curr.listFiles.partition(_.isDirectory)
        val rest = dirs.tail ++ newDirs
        val hasDiagram = files exists { f =>
          val name = f.getName
          if (name.endsWith(".html") && !name.startsWith("index-") &&
            !(name.compare("index.html") == 0) && !(name.compare("package.html") == 0)) {
            val source = scala.io.Source.fromFile(f)
            val hd = source.getLines().exists(_.contains("<div class=\"toggleContainer block diagram-container\" id=\"inheritance-diagram-container\">"))
            source.close()
            hd
          }
          else false
        }
        hasDiagram || findHTMLFileWithDiagram(rest)
      }
    }

    // if we have generated scaladoc and none of the files have a diagram then fail
    if (file.exists() && !findHTMLFileWithDiagram(List(file)))
      sys.error("ScalaDoc diagrams not generated!")
    else
      file
  }

  case class FilterAnyProblem(name: String) extends com.typesafe.tools.mima.core.ProblemFilter {
    import com.typesafe.tools.mima.core._
    override def apply(p: Problem): Boolean = p match {
      case t: TemplateProblem => t.ref.fullName != name && t.ref.fullName != (name + '$')
      case m: MemberProblem => m.ref.owner.fullName != name && m.ref.owner.fullName != (name + '$')
    }
  }

  lazy val mimaIgnoredProblems = {
    import com.typesafe.tools.mima.core._
    Seq(
      // add filters here, see release-2.2 branch
      FilterAnyProblem("akka.remote.testconductor.Terminate"),
      FilterAnyProblem("akka.remote.testconductor.TerminateMsg"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.testconductor.Conductor.shutdown"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.testkit.MultiNodeSpec.akka$remote$testkit$MultiNodeSpec$$deployer"),
      FilterAnyProblem("akka.remote.EndpointManager$Pass"),
      FilterAnyProblem("akka.remote.EndpointManager$EndpointRegistry"),
      FilterAnyProblem("akka.remote.EndpointWriter"),
      FilterAnyProblem("akka.remote.EndpointWriter$StopReading"),
      FilterAnyProblem("akka.remote.EndpointWriter$State"),
      FilterAnyProblem("akka.remote.EndpointWriter$TakeOver"),

      // Change of internal message by #15109
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor#GotUid.copy"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor#GotUid.this"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.remote.ReliableDeliverySupervisor$GotUid$"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor#GotUid.apply")
    )
  }

  lazy val mimaSettings = mimaDefaultSettings ++ Seq(
    // MiMa
    previousArtifact := None,
    binaryIssueFilters ++= mimaIgnoredProblems
  )

  def akkaPreviousArtifact(id: String, organization: String = "com.typesafe.akka", version: String = "2.3.0",
      crossVersion: String = "2.10"): Option[sbt.ModuleID] =
    if (enableMiMa) {
      val fullId = if (crossVersion.isEmpty) id else id + "_" + crossVersion
      Some(organization % fullId % version) // the artifact to compare binary compatibility with
    }
    else None

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

  // OSGi settings

  object OSGi {

    // The included osgiSettings that creates bundles also publish the jar files
    // in the .../bundles directory which makes testing locally published artifacts
    // a pain. Create bundles but publish them to the normal .../jars directory.
    def osgiSettings = defaultOsgiSettings ++ Seq(
      packagedArtifact in (Compile, packageBin) <<= (artifact in (Compile, packageBin), OsgiKeys.bundle).identityMap
    )

    val actor = osgiSettings ++ Seq(
      OsgiKeys.exportPackage := Seq("akka*"),
      OsgiKeys.privatePackage := Seq("akka.osgi.impl"),
      //akka-actor packages are not imported, as contained in the CP
      OsgiKeys.importPackage := (osgiOptionalImports map optionalResolution) ++ Seq("!sun.misc", scalaImport(), configImport(), "*"),
      // dynamicImportPackage needed for loading classes defined in configuration
      OsgiKeys.dynamicImportPackage := Seq("*")
     )

    val agent = exports(Seq("akka.agent.*"))

    val camel = exports(Seq("akka.camel.*"))

    val cluster = exports(Seq("akka.cluster.*"), imports = Seq(protobufImport()))

    val parsing = exports(Seq("akka.parboiled2.*", "akka.shapeless.*"))

    val httpCore = exports(Seq("akka.http.*"))

    val stream = exports(Seq("akka.stream.*"))

    val fileMailbox = exports(Seq("akka.actor.mailbox.filebased.*"))

    val mailboxesCommon = exports(Seq("akka.actor.mailbox.*"), imports = Seq(protobufImport()))

    val osgi = exports(Seq("akka.osgi.*"))

    val osgiDiningHakkersSampleApi = exports(Seq("akka.sample.osgi.api"))

    val osgiDiningHakkersSampleCommand = osgiSettings ++ Seq(OsgiKeys.bundleActivator := Option("akka.sample.osgi.command.Activator"), OsgiKeys.privatePackage := Seq("akka.sample.osgi.command"))

    val osgiDiningHakkersSampleCore = exports(Seq("")) ++ Seq(OsgiKeys.bundleActivator := Option("akka.sample.osgi.activation.Activator"), OsgiKeys.privatePackage := Seq("akka.sample.osgi.internal", "akka.sample.osgi.activation", "akka.sample.osgi.service"))

    val osgiDiningHakkersSampleUncommons = exports(Seq("org.uncommons.maths.random")) ++ Seq(OsgiKeys.privatePackage := Seq("org.uncommons.maths.binary", "org.uncommons.maths", "org.uncommons.maths.number"))

    val remote = exports(Seq("akka.remote.*"), imports = Seq(protobufImport()))

    val slf4j = exports(Seq("akka.event.slf4j.*"))

    val dataflow = exports(Seq("akka.dataflow.*"))

    val transactor = exports(Seq("akka.transactor.*"))

    val persistence = exports(Seq("akka.persistence.*"), imports = Seq(protobufImport()))

    val testkit = exports(Seq("akka.testkit.*"))

    val zeroMQ = exports(Seq("akka.zeromq.*"), imports = Seq(protobufImport()) )

    val osgiOptionalImports = Seq(
      // needed because testkit is normally not used in the application bundle,
      // but it should still be included as transitive dependency and used by BundleDelegatingClassLoader
      // to be able to find refererence.conf
      "akka.testkit",
      "com.google.protobuf")

    def exports(packages: Seq[String] = Seq(), imports: Seq[String] = Nil) = osgiSettings ++ Seq(
      OsgiKeys.importPackage := imports ++ defaultImports,
      OsgiKeys.exportPackage := packages
    )
    def defaultImports = Seq("!sun.misc", akkaImport(), configImport(), scalaImport(), "*")
    def akkaImport(packageName: String = "akka.*") = versionedImport(packageName, "2.3", "2.4")
    def configImport(packageName: String = "com.typesafe.config.*") = versionedImport(packageName, "1.2.0", "1.3.0")
    def protobufImport(packageName: String = "com.google.protobuf.*") = versionedImport(packageName, "2.5.0", "2.6.0")
    def scalaImport(packageName: String = "scala.*") = versionedImport(packageName, s"$scalaEpoch.$scalaMajor", s"$scalaEpoch.${scalaMajor+1}")
    def optionalResolution(packageName: String) = "%s;resolution:=optional".format(packageName)
    def versionedImport(packageName: String, lower: String, upper: String) = s"""$packageName;version="[$lower,$upper)""""
  }
}

// Dependencies

object Dependencies {

  object Versions {
    val scalaStmVersion  = System.getProperty("akka.build.scalaStmVersion", "0.7")
    val scalaZeroMQVersion = System.getProperty("akka.build.scalaZeroMQVersion", "0.0.7")
    val genJavaDocVersion = System.getProperty("akka.build.genJavaDocVersion", "0.7")
    val scalaTestVersion = System.getProperty("akka.build.scalaTestVersion", "2.1.3")
    val scalaCheckVersion = System.getProperty("akka.build.scalaCheckVersion", "1.11.3")
    val scalaContinuationsVersion = System.getProperty("akka.build.scalaContinuationsVersion", "1.0.1")
  }

  object Compile {
    import Versions._

    // Several dependencies are mirrored in the OSGi Dining Hackers maven project
    // They need to be changed in this file as well:
    //   akka-samples/akka-sample-osgi-dining-hakkers/pom.xml

    // Compile
    val camelCore     = "org.apache.camel"            % "camel-core"                   % "2.10.3" exclude("org.slf4j", "slf4j-api") // ApacheV2

    val config        = "com.typesafe"                % "config"                       % "1.2.0"       // ApacheV2
    // mirrored in OSGi sample
    val netty         = "io.netty"                    % "netty"                        % "3.8.0.Final" // ApacheV2
    // mirrored in OSGi sample
    val protobuf      = "com.google.protobuf"         % "protobuf-java"                % "2.5.0"       // New BSD
    val scalaStm      = "org.scala-stm"              %% "scala-stm"                    % scalaStmVersion // Modified BSD (Scala)

    val slf4jApi      = "org.slf4j"                   % "slf4j-api"                    % "1.7.5"       // MIT
    val zeroMQClient  = "org.zeromq"                 %% "zeromq-scala-binding"         % scalaZeroMQVersion // ApacheV2
    // mirrored in OSGi sample
    val uncommonsMath = "org.uncommons.maths"         % "uncommons-maths"              % "1.2.2a" exclude("jfree", "jcommon") exclude("jfree", "jfreechart")      // ApacheV2
    // mirrored in OSGi sample
    val osgiCore      = "org.osgi"                    % "org.osgi.core"                % "4.3.1"       // ApacheV2
    val osgiCompendium= "org.osgi"                    % "org.osgi.compendium"          % "4.3.1"       // ApacheV2
    // mirrored in OSGi sample
    val levelDB       = "org.iq80.leveldb"            % "leveldb"                      % "0.5"         // ApacheV2
    // mirrored in OSGi sample
    val levelDBNative = "org.fusesource.leveldbjni"   % "leveldbjni-all"               % "1.7"         // New BSD

    val reactiveStreams = "org.reactivestreams"       % "reactive-streams-spi"         % "0.3"         // CC0

    // Camel Sample
    val camelJetty  = "org.apache.camel"              % "camel-jetty"                  % camelCore.revision // ApacheV2

    // Cluster Sample
    val sigar       = "org.fusesource"                % "sigar"                        % "1.6.4"            // ApacheV2

    // Compiler plugins
    val genjavadoc    = compilerPlugin("com.typesafe.genjavadoc" %% "genjavadoc-plugin" % genJavaDocVersion cross CrossVersion.full) // ApacheV2

    // Test

    object Test {
      val commonsMath  = "org.apache.commons"          % "commons-math"                 % "2.1"              % "test" // ApacheV2
      val commonsIo    = "commons-io"                  % "commons-io"                   % "2.4"              % "test" // ApacheV2
      val commonsCodec = "commons-codec"               % "commons-codec"                % "1.7"              % "test" // ApacheV2
      val junit        = "junit"                       % "junit"                        % "4.11"             % "test" // Common Public License 1.0
      val logback      = "ch.qos.logback"              % "logback-classic"              % "1.0.13"           % "test" // EPL 1.0 / LGPL 2.1
      val mockito      = "org.mockito"                 % "mockito-all"                  % "1.8.1"            % "test" // MIT
      // changing the scalatest dependency must be reflected in akka-docs/rst/dev/multi-jvm-testing.rst
      // mirrored in OSGi sample
      val scalatest    = "org.scalatest"              %% "scalatest"                    % scalaTestVersion   % "test" // ApacheV2
      val scalacheck   = "org.scalacheck"             %% "scalacheck"                   % scalaCheckVersion  % "test" // New BSD
      val pojosr       = "com.googlecode.pojosr"       % "de.kalpatec.pojosr.framework" % "0.2.1"            % "test" // ApacheV2
      val tinybundles  = "org.ops4j.pax.tinybundles"   % "tinybundles"                  % "1.0.0"            % "test" // ApacheV2
      val log4j        = "log4j"                       % "log4j"                        % "1.2.14"           % "test" // ApacheV2
      val junitIntf    = "com.novocode"                % "junit-interface"              % "0.10"             % "test" // MIT
      // dining hakkers integration test using pax-exam
      // mirrored in OSGi sample
      val karafExam    = "org.apache.karaf.tooling.exam" % "org.apache.karaf.tooling.exam.container" % "2.3.1" % "test" // ApacheV2
      // mirrored in OSGi sample
      val paxExam      = "org.ops4j.pax.exam"          % "pax-exam-junit4"              % "2.6.0"            % "test" // ApacheV2

      val reactiveStreams = "org.reactivestreams"      % "reactive-streams-tck"         % "0.3"              % "test" // CC0
      val scalaXml     = "org.scala-lang.modules"      %% "scala-xml"                   % "1.0.1" % "test"

      // metrics, measurements, perf testing
      val metrics         = "com.codahale.metrics"        % "metrics-core"                 % "3.0.1"            % "test" // ApacheV2
      val metricsJvm      = "com.codahale.metrics"        % "metrics-jvm"                  % "3.0.1"            % "test" // ApacheV2
      val metricsGraphite = "com.codahale.metrics"        % "metrics-graphite"             % "3.0.1"            % "test" // ApacheV2
      val latencyUtils    = "org.latencyutils"            % "LatencyUtils"                 % "1.0.3"            % "test" // Free BSD
      val hdrHistogram    = "org.hdrhistogram"            % "HdrHistogram"                 % "1.1.4"            % "test" // CC0
      val metricsAll      = Seq(metrics, metricsJvm, metricsGraphite, latencyUtils, hdrHistogram)
    }
  }

  import Compile._

  val scalaXmlDepencency = (if (AkkaBuild.requestedScalaVersion.startsWith("2.10")) Nil else Seq(Test.scalaXml))

  val actor = Seq(config)

  val testkit = Seq(Test.junit, Test.scalatest) ++ Test.metricsAll

  val actorTests = Seq(Test.junit, Test.scalatest, Test.commonsCodec, Test.commonsMath, Test.mockito, Test.scalacheck, protobuf, Test.junitIntf)

  val remote = Seq(netty, protobuf, uncommonsMath, Test.junit, Test.scalatest)

  val remoteTests = Seq(Test.junit, Test.scalatest) ++ scalaXmlDepencency

  val cluster = Seq(Test.junit, Test.scalatest)

  val slf4j = Seq(slf4jApi, Test.logback)

  val agent = Seq(scalaStm, Test.scalatest, Test.junit)

  val transactor = Seq(scalaStm, Test.scalatest, Test.junit)

  val persistence = Seq(levelDB, levelDBNative, protobuf, Test.scalatest, Test.junit, Test.commonsIo) ++
    scalaXmlDepencency

  val httpCore = Seq(
    // FIXME switch back to project dependency
    "com.typesafe.akka" %% "akka-testkit" % "2.3.3" % "test",
    Test.junit, Test.scalatest)

  val stream = Seq(
    // FIXME use project dependency when akka-stream-experimental-2.3.x is released
    "com.typesafe.akka" %% "akka-actor" % "2.3.3",
    "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.3",
    "com.typesafe.akka" %% "akka-testkit" % "2.3.3" % "test",
    Test.scalatest, Test.scalacheck, Test.junit, reactiveStreams, Test.reactiveStreams, Test.commonsIo)

  val mailboxes = Seq(Test.scalatest, Test.junit)

  val fileMailbox = Seq(Test.commonsIo, Test.scalatest, Test.junit)

  val kernel = Seq(Test.scalatest, Test.junit)

  val camel = Seq(camelCore, Test.scalatest, Test.junit, Test.mockito, Test.logback, Test.commonsIo, Test.junitIntf)

  val camelSample = Seq(camelJetty)

  val osgi = Seq(osgiCore, osgiCompendium, Test.logback, Test.commonsIo, Test.pojosr, Test.tinybundles, Test.scalatest, Test.junit)

  val osgiDiningHakkersSampleCore = Seq(config, osgiCore, osgiCompendium)

  val osgiDiningHakkersSampleCommand = Seq(osgiCore, osgiCompendium)

  val osgiDiningHakkersSampleTest = Seq(osgiCore, osgiCompendium, Test.karafExam, Test.paxExam, Test.junit, Test.scalatest)

  val uncommons = Seq(uncommonsMath)

  val docs = Seq(Test.scalatest, Test.junit, Test.junitIntf)

  val zeroMQ = Seq(protobuf, zeroMQClient, Test.scalatest, Test.junit)

  val clusterSample = Seq(Test.scalatest, sigar)

  val contrib = Seq(Test.junitIntf, Test.commonsIo)

  val multiNodeSample = Seq(Test.scalatest)
}
