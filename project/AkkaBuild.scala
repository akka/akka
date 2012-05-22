/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import sbt.Keys._
import com.typesafe.sbtmultijvm.MultiJvmPlugin
import com.typesafe.sbtmultijvm.MultiJvmPlugin.{ MultiJvm, extraOptions, jvmOptions, scalatestOptions }
import com.typesafe.sbtscalariform.ScalariformPlugin
import com.typesafe.sbtscalariform.ScalariformPlugin.ScalariformKeys
import java.lang.Boolean.getBoolean
import Sphinx.{ sphinxDocs, sphinxHtml, sphinxLatex, sphinxPdf, sphinxPygments, sphinxTags }

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  lazy val buildSettings = Seq(
    organization := "com.typesafe.akka",
    version      := "2.1-SNAPSHOT",
    scalaVersion := "2.9.2"
  )

  lazy val akka = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Release.settings ++ Unidoc.settings ++ Sphinx.settings ++ Publish.versionSettings ++ Dist.settings ++ Seq(
      testMailbox in GlobalScope := System.getProperty("akka.testMailbox", "false").toBoolean,
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Publish.defaultPublishTo in ThisBuild <<= crossTarget / "repository",
      Unidoc.unidocExclude := Seq(samples.id, tutorials.id),
      Dist.distExclude := Seq(actorTests.id, akkaSbtPlugin.id, docs.id),
      // online version of docs
      sphinxDocs <<= baseDirectory / "akka-docs",
      sphinxTags in sphinxHtml += "online",
      sphinxPygments <<= sphinxPygments in LocalProject(docs.id),
      sphinxLatex <<= sphinxLatex in LocalProject(docs.id),
      sphinxPdf <<= sphinxPdf in LocalProject(docs.id)
    ),
    aggregate = Seq(actor, testkit, actorTests, remote, camel, cluster, slf4j, agent, transactor, mailboxes, zeroMQ, kernel, akkaSbtPlugin, samples, tutorials, docs)
  )

  lazy val actor = Project(
    id = "akka-actor",
    base = file("akka-actor"),
    settings = defaultSettings ++ Seq(
      autoCompilerPlugins := true,
      libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % v) },
      scalacOptions += "-P:continuations:enable",
      // to fix scaladoc generation
      fullClasspath in doc in Compile <<= fullClasspath in Compile
    )
  )

  lazy val testkit = Project(
    id = "akka-testkit",
    base = file("akka-testkit"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.testkit
    )
  )

  lazy val actorTests = Project(
    id = "akka-actor-tests",
    base = file("akka-actor-tests"),
    dependencies = Seq(testkit % "compile;test->test"),
    settings = defaultSettings ++ Seq(
      autoCompilerPlugins := true,
      libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % v) },
      scalacOptions += "-P:continuations:enable",
      libraryDependencies ++= Dependencies.actorTests
    )
  )

  lazy val remote = Project(
    id = "akka-remote",
    base = file("akka-remote"),
    dependencies = Seq(actor, actorTests % "test->test", testkit % "test->test"),
    settings = defaultSettings ++ multiJvmSettings ++ Seq(
      libraryDependencies ++= Dependencies.remote,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := Seq("-r", "org.scalatest.akka.QuietReporter"),
      jvmOptions in MultiJvm := {
        if (getBoolean("sbt.log.noformat")) Seq("-Dakka.test.nocolor=true") else Nil
      },
      test in Test <<= (test in Test) dependsOn (test in MultiJvm)
    )
  ) configs (MultiJvm)

  lazy val cluster = Project(
    id = "akka-cluster",
    base = file("akka-cluster"),
    dependencies = Seq(remote, remote % "test->test", testkit % "test->test"),
    settings = defaultSettings ++ multiJvmSettings ++ Seq(
      libraryDependencies ++= Dependencies.cluster,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := Seq("-r", "org.scalatest.akka.QuietReporter"),
      jvmOptions in MultiJvm := {
        if (getBoolean("sbt.log.noformat")) Seq("-Dakka.test.nocolor=true") else Nil
      },
      test in Test <<= (test in Test) dependsOn (test in MultiJvm)
    )
  ) configs (MultiJvm)

  lazy val slf4j = Project(
    id = "akka-slf4j",
    base = file("akka-slf4j"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.slf4j
    )
  )

  lazy val agent = Project(
    id = "akka-agent",
    base = file("akka-agent"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.agent
    )
  )

  lazy val transactor = Project(
    id = "akka-transactor",
    base = file("akka-transactor"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.transactor
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
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.mailboxes,
      // DurableMailboxSpec published in akka-mailboxes-common-test
      publishArtifact in Test := true
    )
  )

  lazy val fileMailbox = Project(
    id = "akka-file-mailbox",
    base = file("akka-durable-mailboxes/akka-file-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test", testkit % "test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.fileMailbox
    )
  )

  lazy val zeroMQ = Project(
    id = "akka-zeromq",
    base = file("akka-zeromq"),
    dependencies = Seq(actor, testkit % "test;test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.zeroMQ
    )
  )

  lazy val kernel = Project(
    id = "akka-kernel",
    base = file("akka-kernel"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.kernel
    )
  )

  lazy val camel = Project(
     id = "akka-camel",
     base = file("akka-camel"),
     dependencies = Seq(actor, slf4j, testkit % "test->test"),
     settings = defaultSettings ++ Seq(
       libraryDependencies ++= Dependencies.camel
     )
  )

  lazy val akkaSbtPlugin = Project(
    id = "akka-sbt-plugin",
    base = file("akka-sbt-plugin"),
    settings = defaultSettings ++ Seq(
      sbtPlugin := true,
      scalaVersion := "2.9.1"
    )
  )

  lazy val samples = Project(
    id = "akka-samples",
    base = file("akka-samples"),
    settings = parentSettings,
    aggregate = Seq(fsmSample, helloSample, helloKernelSample, remoteSample)
  )

  lazy val fsmSample = Project(
    id = "akka-sample-fsm",
    base = file("akka-samples/akka-sample-fsm"),
    dependencies = Seq(actor),
    settings = defaultSettings
  )

  lazy val helloSample = Project(
    id = "akka-sample-hello",
    base = file("akka-samples/akka-sample-hello"),
    dependencies = Seq(actor),
    settings = defaultSettings
  )

  lazy val helloKernelSample = Project(
    id = "akka-sample-hello-kernel",
    base = file("akka-samples/akka-sample-hello-kernel"),
    dependencies = Seq(kernel),
    settings = defaultSettings
  )

  lazy val remoteSample = Project(
    id = "akka-sample-remote",
    base = file("akka-samples/akka-sample-remote"),
    dependencies = Seq(actor, remote, kernel),
    settings = defaultSettings
  )

  lazy val tutorials = Project(
    id = "akka-tutorials",
    base = file("akka-tutorials"),
    settings = parentSettings,
    aggregate = Seq(firstTutorial)
  )

  lazy val firstTutorial = Project(
    id = "akka-tutorial-first",
    base = file("akka-tutorials/akka-tutorial-first"),
    dependencies = Seq(actor, testkit),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.tutorials
    )
  )

  lazy val docs = Project(
    id = "akka-docs",
    base = file("akka-docs"),
    dependencies = Seq(actor, testkit % "test->test", mailboxesCommon % "compile;test->test",
      remote, cluster, slf4j, agent, transactor, fileMailbox, zeroMQ, camel),
    settings = defaultSettings ++ Sphinx.settings ++ Seq(
      unmanagedSourceDirectories in Test <<= baseDirectory { _ ** "code" get },
      libraryDependencies ++= Dependencies.docs,
      unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test
    )
  )

  // Settings

  override lazy val settings = super.settings ++ buildSettings ++ Seq(
      resolvers += "Sonatype Snapshot Repo" at "https://oss.sonatype.org/content/repositories/snapshots/",
      shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
    )

  lazy val baseSettings = Defaults.defaultSettings ++ Publish.settings

  lazy val parentSettings = baseSettings ++ Seq(
    publishArtifact in Compile := false
  )

  val excludeTestNames = SettingKey[Seq[String]]("exclude-test-names")
  val excludeTestTags = SettingKey[Seq[String]]("exclude-test-tags")
  val includeTestTags = SettingKey[Seq[String]]("include-test-tags")

  val defaultExcludedTags = Seq("timing", "long-running")

  lazy val defaultSettings = baseSettings ++ formatSettings ++ Seq(
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",

    // compile options
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked") ++ (
      if (true || (System getProperty "java.runtime.version" startsWith "1.7")) Seq() else Seq("-optimize")), // -optimize fails with jdk7
    javacOptions  ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),

    ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet,

    parallelExecution in Test := System.getProperty("akka.parallelExecution", "false").toBoolean,

    // for excluding tests by name (or use system property: -Dakka.test.names.exclude=TimingSpec)
    excludeTestNames := {
      val exclude = System.getProperty("akka.test.names.exclude", "")
      if (exclude.isEmpty) Seq.empty else exclude.split(",").toSeq
    },

    // for excluding tests by tag (or use system property: -Dakka.test.tags.exclude=timing)
    excludeTestTags := {
      val exclude = System.getProperty("akka.test.tags.exclude", "")
      if (exclude.isEmpty) defaultExcludedTags else exclude.split(",").toSeq
    },

    // for including tests by tag (or use system property: -Dakka.test.tags.include=timing)
    includeTestTags := {
      val include = System.getProperty("akka.test.tags.include", "")
      if (include.isEmpty) Seq.empty else include.split(",").toSeq
    },

    // add filters for tests excluded by name
    testOptions in Test <++= excludeTestNames map { _.map(exclude => Tests.Filter(test => !test.contains(exclude))) },

    // add arguments for tests excluded by tag - includes override excludes (opposite to scalatest)
    testOptions in Test <++= (excludeTestTags, includeTestTags) map { (excludes, includes) =>
      val tags = (excludes.toSet -- includes.toSet).toSeq
      if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-l", tags.mkString(" ")))
    },

    // add arguments for tests included by tag
    testOptions in Test <++= includeTestTags map { tags =>
      if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-n", tags.mkString(" ")))
    },

    // show full stack traces
    testOptions in Test += Tests.Argument("-oF")
  )

  lazy val formatSettings = ScalariformPlugin.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test    := formattingPreferences
  )

  def formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
  }

  lazy val multiJvmSettings = MultiJvmPlugin.settings ++ inConfig(MultiJvm)(ScalariformPlugin.scalariformSettings) ++ Seq(
    compileInputs in MultiJvm <<= (compileInputs in MultiJvm) dependsOn (ScalariformKeys.format in MultiJvm),
    ScalariformKeys.preferences in MultiJvm := formattingPreferences
  )
}

// Dependencies

object Dependencies {
  import Dependency._

  val testkit = Seq(Test.scalatest, Test.junit)

  val actorTests = Seq(
    Test.junit, Test.scalatest, Test.commonsMath, Test.mockito,
    Test.scalacheck, protobuf
  )

  val remote = Seq(
    netty, protobuf, Test.junit, Test.scalatest,
    Test.zookeeper, Test.log4j // needed for ZkBarrier in multi-jvm tests
  )

  val cluster = Seq(Test.junit, Test.scalatest)

  val slf4j = Seq(slf4jApi, Test.logback)

  val agent = Seq(scalaStm, Test.scalatest, Test.junit)

  val transactor = Seq(scalaStm, Test.scalatest, Test.junit)

  val mailboxes = Seq(Test.scalatest, Test.junit)

  val fileMailbox = Seq(Test.commonsIo, Test.scalatest, Test.junit)

  val kernel = Seq(Test.scalatest, Test.junit)

  val camel = Seq(camelCore, Test.scalatest, Test.junit, Test.mockito)

  val tutorials = Seq(Test.scalatest, Test.junit)

  val docs = Seq(Test.scalatest, Test.junit)

  val zeroMQ = Seq(protobuf, Dependency.zeroMQ, Test.scalatest, Test.junit)
}

object Dependency {

  // Versions

  object V {
    val Camel        = "2.8.0"
    val Logback      = "0.9.28"
    val Netty        = "3.3.0.Final"
    val Protobuf     = "2.4.1"
    val ScalaStm     = "0.5"
    val Scalatest    = "1.6.1"
    val Slf4j        = "1.6.4"
  }

  // Compile

  val camelCore     = "org.apache.camel"            % "camel-core"             % V.Camel      // ApacheV2
  val netty         = "io.netty"                    % "netty"                  % V.Netty      // ApacheV2
  val protobuf      = "com.google.protobuf"         % "protobuf-java"          % V.Protobuf   // New BSD
  val scalaStm      = "org.scala-tools"             % "scala-stm_2.9.1"        % V.ScalaStm   // Modified BSD (Scala)
  val slf4jApi      = "org.slf4j"                   % "slf4j-api"              % V.Slf4j      // MIT
  val zeroMQ        = "org.zeromq"                  % "zeromq-scala-binding_2.9.1"  % "0.0.6" // ApacheV2

  // Runtime

  object Runtime {
    val logback    = "ch.qos.logback"      % "logback-classic" % V.Logback    % "runtime" // MIT
  }

  // Test

  object Test {
    val commonsMath = "org.apache.commons"          % "commons-math"        % "2.1"        % "test" // ApacheV2
    val commonsIo     = "commons-io"                % "commons-io"          % "2.0.1"      % "test"// ApacheV2
    val junit       = "junit"                       % "junit"               % "4.5"        % "test" // Common Public License 1.0
    val logback     = "ch.qos.logback"              % "logback-classic"     % V.Logback    % "test" // EPL 1.0 / LGPL 2.1
    val mockito     = "org.mockito"                 % "mockito-all"         % "1.8.1"      % "test" // MIT
    val scalatest   = "org.scalatest"               % "scalatest_2.9.1"     % V.Scalatest  % "test" // ApacheV2
    val scalacheck  = "org.scala-tools.testing"     % "scalacheck_2.9.1"    % "1.9"        % "test" // New BSD
    val zookeeper   = "org.apache.hadoop.zookeeper" % "zookeeper"           % "3.4.0"      % "test" // ApacheV2
    val log4j       = "log4j"                       % "log4j"               % "1.2.14"     % "test" // ApacheV2
  }
}
