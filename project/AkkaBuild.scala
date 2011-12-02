/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import Keys._

import com.typesafe.sbtmultijvm.MultiJvmPlugin
import com.typesafe.sbtscalariform.ScalariformPlugin

import MultiJvmPlugin.{ MultiJvm, extraOptions, jvmOptions, scalatestOptions }
import ScalariformPlugin.{ format, formatPreferences, formatSourceDirectories }

import java.lang.Boolean.getBoolean

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  lazy val buildSettings = Seq(
    organization := "com.typesafe.akka",
    version      := "2.0-SNAPSHOT",
    scalaVersion := "2.9.1"
  )

  lazy val akka = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Unidoc.settings ++ rstdocSettings ++ Seq(
      parallelExecution in GlobalScope := false,
      Unidoc.unidocExclude := Seq(samples.id, tutorials.id),
      rstdocDirectory <<= baseDirectory / "akka-docs"
    ),
    aggregate = Seq(actor, testkit, actorTests, stm, remote, slf4j, amqp, mailboxes, akkaSbtPlugin, samples, tutorials, docs)
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

  lazy val stm = Project(
    id = "akka-stm",
    base = file("akka-stm"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.stm
    )
  )

  lazy val remote = Project(
    id = "akka-remote",
    base = file("akka-remote"),
    dependencies = Seq(stm, actorTests % "test->test", testkit % "test->test"),
    settings = defaultSettings /*++ multiJvmSettings*/ ++ Seq(
      libraryDependencies ++= Dependencies.cluster,
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

  lazy val amqp = Project(
    id = "akka-amqp",
    base = file("akka-amqp"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.amqp
    )
  )

  lazy val mailboxes = Project(
    id = "akka-durable-mailboxes",
    base = file("akka-durable-mailboxes"),
    settings = parentSettings,
    aggregate = Seq(mailboxesCommon, fileMailbox, mongoMailbox, redisMailbox, beanstalkMailbox, zookeeperMailbox)
  )

  lazy val mailboxesCommon = Project(
    id = "akka-mailboxes-common",
    base = file("akka-durable-mailboxes/akka-mailboxes-common"),
    dependencies = Seq(remote, testkit % "compile;test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.mailboxes
    )
  )

  val testBeanstalkMailbox = SettingKey[Boolean]("test-beanstalk-mailbox")

  lazy val beanstalkMailbox = Project(
    id = "akka-beanstalk-mailbox",
    base = file("akka-durable-mailboxes/akka-beanstalk-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.beanstalkMailbox,
      testBeanstalkMailbox := false,
      testOptions in Test <+= testBeanstalkMailbox map { test => Tests.Filter(s => test) }
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

  val testRedisMailbox = SettingKey[Boolean]("test-redis-mailbox")

  lazy val redisMailbox = Project(
    id = "akka-redis-mailbox",
    base = file("akka-durable-mailboxes/akka-redis-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.redisMailbox,
      testRedisMailbox := false,
      testOptions in Test <+= testRedisMailbox map { test => Tests.Filter(s => test) }
    )
  )

  lazy val zookeeperMailbox = Project(
    id = "akka-zookeeper-mailbox",
    base = file("akka-durable-mailboxes/akka-zookeeper-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test", testkit % "test"),
    settings = defaultSettings  ++ Seq(
      libraryDependencies ++= Dependencies.zookeeperMailbox
    )
  )

  val testMongoMailbox = SettingKey[Boolean]("test-mongo-mailbox")

  lazy val mongoMailbox = Project(
    id = "akka-mongo-mailbox",
    base = file("akka-durable-mailboxes/akka-mongo-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.mongoMailbox,
      testMongoMailbox := false,
      testOptions in Test <+= testMongoMailbox map { test => Tests.Filter(s => test) }
    )
  )

  // lazy val spring = Project(
  //   id = "akka-spring",
  //   base = file("akka-spring"),
  //   dependencies = Seq(cluster, camel),
  //   settings = defaultSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.spring
  //   )
  // )

  // lazy val kernel = Project(
  //   id = "akka-kernel",
  //   base = file("akka-kernel"),
  //   dependencies = Seq(cluster, slf4j, spring),
  //   settings = defaultSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.kernel
  //   )
  // )

  lazy val akkaSbtPlugin = Project(
    id = "akka-sbt-plugin",
    base = file("akka-sbt-plugin"),
    settings = defaultSettings ++ Seq(
      sbtPlugin := true
    )
  )

  lazy val samples = Project(
    id = "akka-samples",
    base = file("akka-samples"),
    settings = parentSettings,
    aggregate = Seq(fsmSample)
  )

  lazy val fsmSample = Project(
    id = "akka-sample-fsm",
    base = file("akka-samples/akka-sample-fsm"),
    dependencies = Seq(actor),
    settings = defaultSettings
  )

  lazy val tutorials = Project(
    id = "akka-tutorials",
    base = file("akka-tutorials"),
    settings = parentSettings,
    aggregate = Seq(firstTutorial, secondTutorial)
  )

  lazy val firstTutorial = Project(
    id = "akka-tutorial-first",
    base = file("akka-tutorials/akka-tutorial-first"),
    dependencies = Seq(actor),
    settings = defaultSettings
  )

  lazy val secondTutorial = Project(
    id = "akka-tutorial-second",
    base = file("akka-tutorials/akka-tutorial-second"),
    dependencies = Seq(actor),
    settings = defaultSettings
  )

  lazy val docs = Project(
    id = "akka-docs",
    base = file("akka-docs"),
    dependencies = Seq(actor, testkit % "test->test", stm, remote, slf4j),
    settings = defaultSettings ++ Seq(
      unmanagedSourceDirectories in Test <<= baseDirectory { _ ** "code" get },
      libraryDependencies ++= Dependencies.docs,
      formatSourceDirectories in Test <<= unmanagedSourceDirectories in Test
    )
  )

  // Settings

  override lazy val settings = super.settings ++ buildSettings ++ Publish.versionSettings

  lazy val baseSettings = Defaults.defaultSettings ++ Publish.settings

  lazy val parentSettings = baseSettings ++ Seq(
    publishArtifact in Compile := false
  )

  val excludeTestNames = SettingKey[Seq[String]]("exclude-test-names")
  val excludeTestTags = SettingKey[Seq[String]]("exclude-test-tags")
  val includeTestTags = SettingKey[Seq[String]]("include-test-tags")

  val defaultExcludedTags = Seq("timing")

  lazy val defaultSettings = baseSettings ++ formatSettings ++ Seq(
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += "Twitter Public Repo" at "http://maven.twttr.com", // This will be going away with com.mongodb.async's next release

    // compile options
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked") ++ (
      if (true || (System getProperty "java.runtime.version" startsWith "1.7")) Seq() else Seq("-optimize")), // -optimize fails with jdk7
    javacOptions  ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),

    // add config dir to classpaths
    unmanagedClasspath in Runtime <+= (baseDirectory in LocalProject("akka")) map { base => Attributed.blank(base / "config") },
    unmanagedClasspath in Test    <+= (baseDirectory in LocalProject("akka")) map { base => Attributed.blank(base / "config") },

    // disable parallel tests
    parallelExecution in Test := false,

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

  lazy val formatSettings = ScalariformPlugin.settings ++ Seq(
    formatPreferences in Compile := formattingPreferences,
    formatPreferences in Test    := formattingPreferences
  )

  def formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
  }

  lazy val multiJvmSettings = MultiJvmPlugin.settings ++ inConfig(MultiJvm)(ScalariformPlugin.formatSettings) ++ Seq(
    compileInputs in MultiJvm <<= (compileInputs in MultiJvm) dependsOn (format in MultiJvm),
    formatPreferences in MultiJvm := formattingPreferences
  )

  // reStructuredText docs

  val rstdocDirectory = SettingKey[File]("rstdoc-directory")
  val rstdoc = TaskKey[File]("rstdoc", "Build the reStructuredText documentation.")

  lazy val rstdocSettings = Seq(rstdoc <<= rstdocTask)

  def rstdocTask = (rstdocDirectory, streams) map {
    (dir, s) => {
      s.log.info("Building reStructuredText documentation...")
      val exitCode = Process(List("make", "clean", "html", "pdf"), dir) ! s.log
      if (exitCode != 0) sys.error("Failed to build docs.")
      s.log.info("Done building docs.")
      dir
    }
  }
}

// Dependencies

object Dependencies {
  import Dependency._

  val testkit = Seq(Test.scalatest, Test.junit)

  val actorTests = Seq(
    Test.junit, Test.scalatest, Test.multiverse, Test.commonsMath, Test.mockito,
    Test.scalacheck, protobuf, jacksonMapper, sjson
  )

  val stm = Seq(multiverse, Test.junit, Test.scalatest)

  val cluster = Seq(
    bookkeeper, commonsCodec, commonsIo, guice, h2Lzf, jacksonCore, jacksonMapper, log4j, netty,
    protobuf, sjson, zkClient, zookeeper, zookeeperLock, Test.junit, Test.scalatest
  )

  val slf4j = Seq(slf4jApi)

  val amqp = Seq(rabbit, commonsIo, protobuf)

  val mailboxes = Seq(Test.scalatest, Test.junit)

  val fileMailbox = Seq(Test.scalatest, Test.junit)

  val beanstalkMailbox = Seq(beanstalk, Test.junit)

  val redisMailbox = Seq(redis, Test.junit)

  val mongoMailbox = Seq(mongoAsync, twttrUtilCore, Test.junit)

  val zookeeperMailbox = Seq(zookeeper, Test.junit)

  val spring = Seq(springBeans, springContext, Test.junit, Test.scalatest)

  val kernel = Seq(
    jettyUtil, jettyXml, jettyServlet, jacksonCore, staxApi
  )

  // TODO: resolve Jetty version conflict
  // val sampleCamel = Seq(camelCore, camelSpring, commonsCodec, Runtime.camelJms, Runtime.activemq, Runtime.springJms,
  //   Test.junit, Test.scalatest, Test.logback)

  val docs = Seq(Test.scalatest, Test.junit)
}

object Dependency {

  // Versions

  object V {
    val Camel        = "2.8.0"
    val Jackson      = "1.8.0"
    val JavaxServlet = "3.0"
    val Jersey       = "1.3"
    val Jetty        = "7.4.0.v20110414"
    val Logback      = "0.9.28"
    val Multiverse   = "0.6.2"
    val Netty        = "3.2.5.Final"
    val Protobuf     = "2.4.1"
    val Scalatest    = "1.6.1"
    val Slf4j        = "1.6.4"
    val Spring       = "3.0.5.RELEASE"
    val Zookeeper    = "3.4.0"
    val Rabbit       = "2.3.1"
  }

  // Compile

  val beanstalk     = "beanstalk"                   % "beanstalk_client"       % "1.4.5"      // New BSD
  val bookkeeper    = "org.apache.hadoop.zookeeper" % "bookkeeper"             % V.Zookeeper  // ApacheV2
  val camelCore     = "org.apache.camel"            % "camel-core"             % V.Camel      // ApacheV2
  val camelSpring   = "org.apache.camel"            % "camel-spring"           % V.Camel      // ApacheV2
  val commonsCodec  = "commons-codec"               % "commons-codec"          % "1.4"        // ApacheV2
  val commonsIo     = "commons-io"                  % "commons-io"             % "2.0.1"      // ApacheV2
  val guice         = "org.guiceyfruit"             % "guice-all"              % "2.0"        // ApacheV2
  val h2Lzf         = "voldemort.store.compress"    % "h2-lzf"                 % "1.0"        // ApacheV2
  val jacksonCore   = "org.codehaus.jackson"        % "jackson-core-asl"       % V.Jackson    // ApacheV2
  val jacksonMapper = "org.codehaus.jackson"        % "jackson-mapper-asl"     % V.Jackson    // ApacheV2
  val jettyUtil     = "org.eclipse.jetty"           % "jetty-util"             % V.Jetty      // Eclipse license
  val jettyXml      = "org.eclipse.jetty"           % "jetty-xml"              % V.Jetty      // Eclipse license
  val jettyServlet  = "org.eclipse.jetty"           % "jetty-servlet"          % V.Jetty      // Eclipse license
  val log4j         = "log4j"                       % "log4j"                  % "1.2.15"     // ApacheV2
  val mongoAsync    = "com.mongodb.async"           % "mongo-driver_2.9.0-1"   % "0.2.9-1"    // ApacheV2
  val multiverse    = "org.multiverse"              % "multiverse-alpha"       % V.Multiverse // ApacheV2
  val netty         = "org.jboss.netty"             % "netty"                  % V.Netty      // ApacheV2
  val osgi          = "org.osgi"                    % "org.osgi.core"          % "4.2.0"      // ApacheV2
  val protobuf      = "com.google.protobuf"         % "protobuf-java"          % V.Protobuf   // New BSD
  val rabbit        = "com.rabbitmq"                % "amqp-client"            % V.Rabbit     // Mozilla Public License
  val redis         = "net.debasishg"               %% "redisclient"           % "2.4.0"      // ApacheV2
  val sjson         = "net.debasishg"               %% "sjson"                 % "0.15"       // ApacheV2
  val slf4jApi      = "org.slf4j"                   % "slf4j-api"              % V.Slf4j      // MIT
  val springBeans   = "org.springframework"         % "spring-beans"           % V.Spring     // ApacheV2
  val springContext = "org.springframework"         % "spring-context"         % V.Spring     // ApacheV2
  val staxApi       = "javax.xml.stream"            % "stax-api"               % "1.0-2"      // ApacheV2
  val twttrUtilCore = "com.twitter"                 % "util-core"              % "1.8.1"      // ApacheV2
  val zkClient      = "zkclient"                    % "zkclient"               % "0.3"        // ApacheV2
  val zookeeper     = "org.apache.hadoop.zookeeper" % "zookeeper"              % V.Zookeeper  // ApacheV2
  val zookeeperLock = "org.apache.hadoop.zookeeper" % "zookeeper-recipes-lock" % V.Zookeeper  // ApacheV2

  // Provided

  object Provided {
    val javaxServlet = "org.apache.geronimo.specs" % "geronimo-servlet_3.0_spec" % "1.0" % "provided" // CDDL v1
    val jetty        = "org.eclipse.jetty" % "jetty-server"  % V.Jetty        % "provided"            // Eclipse license
  }

  // Runtime

  object Runtime {
    val activemq   = "org.apache.activemq" % "activemq-core"   % "5.4.2"      % "runtime" // ApacheV2
    val camelJetty = "org.apache.camel"    % "camel-jetty"     % V.Camel      % "runtime" // ApacheV2
    val camelJms   = "org.apache.camel"    % "camel-jms"       % V.Camel      % "runtime" // ApacheV2
    val logback    = "ch.qos.logback"      % "logback-classic" % V.Logback    % "runtime" // MIT
    val springJms  = "org.springframework" % "spring-jms"      % V.Spring     % "runtime" // ApacheV2
  }

  // Test

  object Test {
    val commonsColl = "commons-collections"     % "commons-collections" % "3.2.1"      % "test" // ApacheV2
    val commonsMath = "org.apache.commons"      % "commons-math"        % "2.1"        % "test" // ApacheV2
    val jetty       = "org.eclipse.jetty"       % "jetty-server"        % V.Jetty      % "test" // Eclipse license
    val jettyWebapp = "org.eclipse.jetty"       % "jetty-webapp"        % V.Jetty      % "test" // Eclipse license
    val junit       = "junit"                   % "junit"               % "4.5"        % "test" // Common Public License 1.0
    val logback     = "ch.qos.logback"          % "logback-classic"     % V.Logback    % "test" // EPL 1.0 / LGPL 2.1
    val mockito     = "org.mockito"             % "mockito-all"         % "1.8.1"      % "test" // MIT
    val multiverse  = "org.multiverse"          % "multiverse-alpha"    % V.Multiverse % "test" // ApacheV2
    val scalatest   = "org.scalatest"           %% "scalatest"          % V.Scalatest  % "test" // ApacheV2
    val scalacheck  = "org.scala-tools.testing" %% "scalacheck"         % "1.9"        % "test" // New BSD
  }
}
