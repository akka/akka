/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import sbt.Keys._
import com.typesafe.sbtmultijvm.MultiJvmPlugin
import com.typesafe.sbtmultijvm.MultiJvmPlugin.{ MultiJvm, extraOptions, jvmOptions, scalatestOptions, multiNodeExecuteTests }
import com.typesafe.sbtscalariform.ScalariformPlugin
import com.typesafe.sbtscalariform.ScalariformPlugin.ScalariformKeys
import com.typesafe.sbtosgi.OsgiPlugin.{ OsgiKeys, osgiSettings }
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
import java.lang.Boolean.getBoolean
import sbt.Tests
import Sphinx.{ sphinxDocs, sphinxHtml, sphinxLatex, sphinxPdf, sphinxPygments, sphinxTags }

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  lazy val desiredScalaVersion = "2.10.0-M4"

  lazy val buildSettings = Seq(
    organization := "com.typesafe.akka",
    version      := "2.1-SNAPSHOT",
    //scalaVersion := "2.10.0-M4"
    scalaVersion := "2.10.0-SNAPSHOT",
    scalaVersion in update <<= (scalaVersion) apply {
      case  "2.10.0-SNAPSHOT" =>  desiredScalaVersion
      case x => x
    }
  )

  lazy val akka = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Release.settings ++ Unidoc.settings ++ Sphinx.settings ++ Publish.versionSettings ++
      Dist.settings ++ mimaSettings ++ Seq(
      testMailbox in GlobalScope := System.getProperty("akka.testMailbox", "false").toBoolean,
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Publish.defaultPublishTo in ThisBuild <<= crossTarget / "repository",
      Unidoc.unidocExclude := Seq(samples.id, tutorials.id),
      Dist.distExclude := Seq(actorTests.id, akkaSbtPlugin.id, docs.id),
      initialCommands in ThisBuild :=
        """|import akka.actor._
           |import akka.dispatch._
           |import com.typesafe.config.ConfigFactory
           |import akka.util.duration._
           |import akka.util.Timeout
           |val config = ConfigFactory.parseString("akka.stdout-loglevel=INFO,akka.loglevel=DEBUG")
           |val remoteConfig = ConfigFactory.parseString("akka.remote.netty{port=0,use-dispatcher-for-io=akka.actor.default-dispatcher,execution-pool-size=0},akka.actor.provider=RemoteActorRefProvider").withFallback(config)
           |var system: ActorSystem = null
           |def startSystem(remoting: Boolean = false) { system = ActorSystem("repl", if(remoting) remoteConfig else config); println("don’t forget to system.shutdown()!") }
           |implicit def ec = system.dispatcher
           |implicit val timeout = Timeout(5 seconds)
           |""".stripMargin,
      initialCommands in Test in ThisBuild += "import akka.testkit._",
      // online version of docs
      sphinxDocs <<= baseDirectory / "akka-docs",
      sphinxTags in sphinxHtml += "online",
      sphinxPygments <<= sphinxPygments in LocalProject(docs.id),
      sphinxLatex <<= sphinxLatex in LocalProject(docs.id),
      sphinxPdf <<= sphinxPdf in LocalProject(docs.id)
    ),
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel, cluster, slf4j, agent, transactor, mailboxes, zeroMQ, kernel, /*akkaSbtPlugin,*/ samples, tutorials, osgi, osgiAries, docs)
  )

  lazy val actor = Project(
    id = "akka-actor",
    base = file("akka-actor"),
    settings = defaultSettings ++ OSGi.actor ++ Seq(
      autoCompilerPlugins := true,
      libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % v) },
      libraryDependencies <+= scalaVersion { v => "org.scala-lang" % "scala-reflect" % v },
      scalacOptions += "-P:continuations:enable",
      packagedArtifact in (Compile, packageBin) <<= (artifact in (Compile, packageBin), OsgiKeys.bundle).identityMap,
      artifact in (Compile, packageBin) ~= (_.copy(`type` = "bundle")),
      // to fix scaladoc generation
      fullClasspath in doc in Compile <<= fullClasspath in Compile,
      libraryDependencies ++= Dependencies.actor,
      previousArtifact := akkaPreviousArtifact("akka-actor")
    )
  )

  lazy val testkit = Project(
    id = "akka-testkit",
    base = file("akka-testkit"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.testkit,
      initialCommands += "import akka.testkit._",
      previousArtifact := akkaPreviousArtifact("akka-testkit")
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
    settings = defaultSettings ++ OSGi.remote ++ Seq(
      libraryDependencies ++= Dependencies.remote,
      // disable parallel tests
      parallelExecution in Test := false
    )
  )

  lazy val remoteTests = Project(
    id = "akka-remote-tests",
    base = file("akka-remote-tests"),
    dependencies = Seq(remote, actorTests % "test->test", testkit % "test->test"),
    settings = defaultSettings ++ multiJvmSettings ++ Seq(
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := defaultMultiJvmScalatestOptions,
      jvmOptions in MultiJvm := defaultMultiJvmOptions,
      previousArtifact := akkaPreviousArtifact("akka-remote")
    )
  ) configs (MultiJvm)

  lazy val cluster = Project(
    id = "akka-cluster",
    base = file("akka-cluster"),
    dependencies = Seq(remote, remoteTests % "compile;test->test;multi-jvm->multi-jvm", testkit % "test->test"),
    settings = defaultSettings ++ multiJvmSettings ++ OSGi.cluster ++ Seq(
      libraryDependencies ++= Dependencies.cluster,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := defaultMultiJvmScalatestOptions,
      jvmOptions in MultiJvm := defaultMultiJvmOptions,
      previousArtifact := akkaPreviousArtifact("akka-remote")
    )
  ) configs (MultiJvm)

  lazy val slf4j = Project(
    id = "akka-slf4j",
    base = file("akka-slf4j"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ OSGi.slf4j ++ Seq(
      libraryDependencies ++= Dependencies.slf4j
    )
  )

  lazy val agent = Project(
    id = "akka-agent",
    base = file("akka-agent"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ OSGi.agent ++ Seq(
      libraryDependencies ++= Dependencies.agent,
      previousArtifact := akkaPreviousArtifact("akka-agent")
    )
  )

  lazy val transactor = Project(
    id = "akka-transactor",
    base = file("akka-transactor"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ OSGi.transactor ++ Seq(
      libraryDependencies ++= Dependencies.transactor,
      previousArtifact := akkaPreviousArtifact("akka-transactor")
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
    settings = defaultSettings ++ OSGi.mailboxesCommon ++ Seq(
      libraryDependencies ++= Dependencies.mailboxes,
      previousArtifact := akkaPreviousArtifact("akka-mailboxes-common"),
        // DurableMailboxSpec published in akka-mailboxes-common-test
      publishArtifact in Test := true
    )
  )

  lazy val fileMailbox = Project(
    id = "akka-file-mailbox",
    base = file("akka-durable-mailboxes/akka-file-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test", testkit % "test"),
    settings = defaultSettings ++ OSGi.fileMailbox ++ Seq(
      libraryDependencies ++= Dependencies.fileMailbox,
      previousArtifact := akkaPreviousArtifact("akka-file-mailbox")
    )
  )

  lazy val zeroMQ = Project(
    id = "akka-zeromq",
    base = file("akka-zeromq"),
    dependencies = Seq(actor, testkit % "test;test->test"),
    settings = defaultSettings ++ OSGi.zeroMQ ++ Seq(
      libraryDependencies ++= Dependencies.zeroMQ,
      previousArtifact := akkaPreviousArtifact("akka-zeromq")
    )
  )

  lazy val kernel = Project(
    id = "akka-kernel",
    base = file("akka-kernel"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.kernel,
      previousArtifact := akkaPreviousArtifact("akka-kernel")
    )
  )

  lazy val camel = Project(
     id = "akka-camel",
     base = file("akka-camel"),
     dependencies = Seq(actor, slf4j, testkit % "test->test"),
     settings = defaultSettings ++ OSGi.camel ++ Seq(
       libraryDependencies ++= Dependencies.camel
     )
  )

  lazy val osgi = Project(
    id = "akka-osgi",
    base = file("akka-osgi"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ OSGi.osgi ++ Seq(
      libraryDependencies ++= Dependencies.osgi,
      parallelExecution in Test := false
    )
  )

  lazy val osgiAries = Project(
    id = "akka-osgi-aries",
    base = file("akka-osgi-aries"),
    dependencies = Seq(osgi % "compile;test->test"),
    settings = defaultSettings ++ OSGi.osgiAries ++ Seq(
      libraryDependencies ++= Dependencies.osgiAries,
      parallelExecution in Test := false
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
      remote, cluster, slf4j, agent, transactor, fileMailbox, zeroMQ, camel, osgi, osgiAries),
    settings = defaultSettings ++ Sphinx.settings ++ Seq(
      unmanagedSourceDirectories in Test <<= baseDirectory { _ ** "code" get },
      libraryDependencies ++= Dependencies.docs,
      unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test
    )
  )

  // Settings

  override lazy val settings = super.settings ++ buildSettings ++ Seq(
      resolvers += "Sonatype Snapshot Repo" at "https://oss.sonatype.org/content/repositories/snapshots/",
      resolvers += "Sonatype Releases Repo" at "https://oss.sonatype.org/content/repositories/releases/",
      shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
    )

  lazy val baseSettings = Defaults.defaultSettings ++ Publish.settings

  lazy val parentSettings = baseSettings ++ Seq(
    publishArtifact in Compile := false
  )

  val excludeTestNames = SettingKey[Seq[String]]("exclude-test-names")
  val excludeTestTags = SettingKey[Set[String]]("exclude-test-tags")
  val includeTestTags = SettingKey[Set[String]]("include-test-tags")
  val onlyTestTags = SettingKey[Set[String]]("only-test-tags")

  val defaultExcludedTags = Set("timing", "long-running")

  lazy val defaultMultiJvmOptions: Seq[String] = {
    import scala.collection.JavaConverters._
    val akkaProperties = System.getProperties.propertyNames.asScala.toList.collect {
      case key: String if key.startsWith("akka.") => "-D" + key + "=" + System.getProperty(key)
    }
    akkaProperties ::: (if (getBoolean("sbt.log.noformat")) List("-Dakka.test.nocolor=true") else Nil)
  }

  // for excluding tests by name use system property: -Dakka.test.names.exclude=TimingSpec
  // not supported by multi-jvm tests
  lazy val useExcludeTestNames: Seq[String] = systemPropertyAsSeq("akka.test.names.exclude")

  // for excluding tests by tag use system property: -Dakka.test.tags.exclude=<tag name>
  // note that it will not be used if you specify -Dakka.test.tags.only
  lazy val useExcludeTestTags: Set[String] = {
    if (useOnlyTestTags.isEmpty) defaultExcludedTags ++ systemPropertyAsSeq("akka.test.tags.exclude").toSet
    else Set.empty
  }

  // for including tests by tag use system property: -Dakka.test.tags.include=<tag name>
  // note that it will not be used if you specify -Dakka.test.tags.only
  lazy val useIncludeTestTags: Set[String] = {
    if (useOnlyTestTags.isEmpty) systemPropertyAsSeq("akka.test.tags.include").toSet
    else Set.empty
  }

  // for running only tests by tag use system property: -Dakka.test.tags.only=<tag name>
  lazy val useOnlyTestTags: Set[String] = systemPropertyAsSeq("akka.test.tags.only").toSet

  def executeMultiJvmTests: Boolean = {
    useOnlyTestTags.contains("long-running") ||
    !(useExcludeTestTags -- useIncludeTestTags).contains("long-running")
  }

  def systemPropertyAsSeq(name: String): Seq[String] = {
    val prop = System.getProperty(name, "")
    if (prop.isEmpty) Seq.empty else prop.split(",").toSeq
  }

  val multiNodeEnabled = java.lang.Boolean.getBoolean("akka.test.multi-node")

  lazy val defaultMultiJvmScalatestOptions: Seq[String] = {
    val excludeTags = (useExcludeTestTags -- useIncludeTestTags).toSeq
    Seq("-r", "org.scalatest.akka.QuietReporter") ++
    (if (excludeTags.isEmpty) Seq.empty else Seq("-l", if (multiNodeEnabled) excludeTags.mkString("\"", " ", "\"") else excludeTags.mkString(" "))) ++
    (if (useOnlyTestTags.isEmpty) Seq.empty else Seq("-n", if (multiNodeEnabled) useOnlyTestTags.mkString("\"", " ", "\"") else useOnlyTestTags.mkString(" ")))
  }

  lazy val defaultSettings = baseSettings ++ formatSettings ++ mimaSettings ++ Seq(
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",

    // compile options
    scalacOptions ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", /*"-deprecation",*/ "-feature", "-unchecked", "-Xlog-reflective-calls") ++ (
      if (true || (System getProperty "java.runtime.version" startsWith "1.7")) Seq() else Seq("-optimize")), // -optimize fails with jdk7
    javacOptions  ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),

    ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet,

    parallelExecution in Test := System.getProperty("akka.parallelExecution", "false").toBoolean,
    logBuffered in Test := System.getProperty("akka.logBufferedTests", "false").toBoolean,

    excludeTestNames := useExcludeTestNames,
    excludeTestTags := useExcludeTestTags,
    includeTestTags := useIncludeTestTags,
    onlyTestTags := useOnlyTestTags,

    // add filters for tests excluded by name
    testOptions in Test <++= excludeTestNames map { _.map(exclude => Tests.Filter(test => !test.contains(exclude))) },

    // add arguments for tests excluded by tag - includes override excludes (opposite to scalatest)
    testOptions in Test <++= (excludeTestTags, includeTestTags) map { (excludes, includes) =>
      val tags = (excludes -- includes)
      if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-l", tags.mkString(" ")))
    },

    // add arguments for running only tests by tag
    testOptions in Test <++= onlyTestTags map { tags =>
      if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-n", tags.mkString(" ")))
    },

    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF")
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
    ScalariformKeys.preferences in MultiJvm := formattingPreferences) ++
    ((executeMultiJvmTests, multiNodeEnabled) match {
      case (true, true) =>
        executeTests in Test <<= ((executeTests in Test), (multiNodeExecuteTests in MultiJvm)) map {
          case ((_, testResults), (_, multiNodeResults))  =>
            val results = testResults ++ multiNodeResults
            (Tests.overall(results.values), results)
        }
      case (true, false) =>
        executeTests in Test <<= ((executeTests in Test), (executeTests in MultiJvm)) map {
          case ((_, testResults), (_, multiNodeResults)) =>
            val results = testResults ++ multiNodeResults
            (Tests.overall(results.values), results)
        }
      case (false, _) => Seq.empty
    })

  lazy val mimaSettings = mimaDefaultSettings ++ Seq(
    // MiMa
    previousArtifact := None
  )

  def akkaPreviousArtifact(id: String, organization: String = "com.typesafe.akka", version: String = "2.0"): Option[sbt.ModuleID] = {
    // the artifact to compare binary compatibility with
    Some(organization % id % version)
  }
}

// Dependencies

object Dependencies {
  import Dependency._

  val actor = Seq(config)

  val testkit = Seq(Test.scalatest, Test.junit)

  val actorTests = Seq(Test.junit, Test.scalatest, Test.commonsMath, Test.mockito, Test.scalacheck, protobuf)

  val remote = Seq(netty, protobuf, uncommonsMath, Test.junit, Test.scalatest)

  val cluster = Seq(Test.junit, Test.scalatest)

  val slf4j = Seq(slf4jApi, Test.logback)

  val agent = Seq(scalaStm, Test.scalatest, Test.junit)

  val transactor = Seq(scalaStm, Test.scalatest, Test.junit)

  val mailboxes = Seq(Test.scalatest, Test.junit)

  val fileMailbox = Seq(Test.commonsIo, Test.scalatest, Test.junit)

  val kernel = Seq(Test.scalatest, Test.junit)

  val camel = Seq(camelCore, Test.scalatest, Test.junit, Test.mockito)

  val osgi = Seq(osgiCore,Test.logback, Test.commonsIo, Test.pojosr, Test.tinybundles, Test.scalatest, Test.junit)

  val osgiAries = Seq(osgiCore, ariesBlueprint, Test.ariesProxy)

  val tutorials = Seq(Test.scalatest, Test.junit)

  val docs = Seq(Test.scalatest, Test.junit, Test.specs2)

  val zeroMQ = Seq(protobuf, Dependency.zeroMQ, Test.scalatest, Test.junit)
}

object Dependency {

  def v(a: String): String = a+"_"+AkkaBuild.desiredScalaVersion

  // Compile
  val config        = "com.typesafe"                % "config"                     % "0.4.1"       // ApacheV2
  val camelCore     = "org.apache.camel"            % "camel-core"                 % "2.8.0"       // ApacheV2
  val netty         = "io.netty"                    % "netty"                      % "3.5.1.Final" // ApacheV2
  val protobuf      = "com.google.protobuf"         % "protobuf-java"              % "2.4.1"       // New BSD
  val scalaStm      = "org.scala-tools"             % v("scala-stm")               % "0.5"         // Modified BSD (Scala)
  val slf4jApi      = "org.slf4j"                   % "slf4j-api"                  % "1.6.4"       // MIT
  val zeroMQ        = "org.zeromq"                  % v("zeromq-scala-binding")    % "0.0.6"       // ApacheV2  //FIXME SWITCH TO OFFICIAL VERSION
  val uncommonsMath = "org.uncommons.maths"         % "uncommons-maths"            % "1.2.2a"      // ApacheV2
  val ariesBlueprint = "org.apache.aries.blueprint" % "org.apache.aries.blueprint" % "0.3.2"       // ApacheV2
  val osgiCore      = "org.osgi"                    % "org.osgi.core"              % "4.2.0"       // ApacheV2

  // Test

  object Test {
    val commonsMath = "org.apache.commons"          % "commons-math"            % "2.1"               % "test" // ApacheV2
    val commonsIo   = "commons-io"                  % "commons-io"              % "2.0.1"             % "test" // ApacheV2
    val junit       = "junit"                       % "junit"                   % "4.10"              % "test" // Common Public License 1.0
    val logback     = "ch.qos.logback"              % "logback-classic"         % "1.0.4"             % "test" // EPL 1.0 / LGPL 2.1
    val mockito     = "org.mockito"                 % "mockito-all"             % "1.8.1"             % "test" // MIT
    val scalatest   = "org.scalatest"               % v("scalatest")            % "1.9-2.10.0-M4-B2"  % "test" // ApacheV2
    val scalacheck  = "org.scalacheck"              % v("scalacheck")           % "1.10.0-b1"         % "test" // New BSD
    val specs2      = "org.specs2"                  % "specs2_2.10"             % "1.11"              % "test" // Modified BSD / ApacheV2
    val ariesProxy  = "org.apache.aries.proxy"      % "org.apache.aries.proxy.impl"  % "0.3" % "test"  // ApacheV2
    val pojosr      = "com.googlecode.pojosr"       % "de.kalpatec.pojosr.framework" % "0.1.4"   % "test" // ApacheV2
    val tinybundles = "org.ops4j.pax.tinybundles"   % "tinybundles"         % "1.0.0"      % "test" // ApacheV2
  }
}

// OSGi settings

object OSGi {

  val actor = exports(Seq("akka*"))

  val agent = exports(Seq("akka.agent.*"))

  val camel = exports(Seq("akka.camel.*"))

  val cluster = exports(Seq("akka.cluster.*"))

  val fileMailbox = exports(Seq("akka.actor.mailbox.*"))

  val mailboxesCommon = exports(Seq("akka.actor.mailbox.*"))

  val osgi = exports(Seq("akka.osgi")) ++ Seq(OsgiKeys.privatePackage := Seq("akka.osgi.impl"))

  val osgiAries = exports() ++ Seq(OsgiKeys.privatePackage := Seq("akka.osgi.aries.*"))

  val remote = exports(Seq("akka.remote.*", "akka.routing.*", "akka.serialization.*"))

  val slf4j = exports(Seq("akka.event.slf4j.*"))

  val transactor = exports(Seq("akka.transactor.*"))

  val zeroMQ = exports(Seq("akka.zeromq.*"))

  def exports(packages: Seq[String] = Seq()) = osgiSettings ++ Seq(
    OsgiKeys.importPackage := defaultImports,
    OsgiKeys.exportPackage := packages
  )

  def defaultImports = Seq("!sun.misc", akkaImport(), configImport(), scalaImport(), "*")
  def akkaImport(packageName: String = "akka.*") = "%s;version=\"[2.1,2.2)\"".format(packageName)
  def configImport(packageName: String = "com.typesafe.config.*") = "%s;version=\"[0.4.1,0.5)\"".format(packageName)
  def scalaImport(packageName: String = "scala.*") = "%s;version=\"[2.9.2,2.10)\"".format(packageName)

}
