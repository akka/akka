import sbt._
import Keys._
import MultiJvmPlugin.{ MultiJvm, extraOptions, jvmOptions, scalatestOptions }
import ScalariformPlugin.{ format, formatPreferences }
import java.lang.Boolean.getBoolean

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  lazy val buildSettings = Seq(
    organization := "se.scalablesolutions.akka",
    version      := "2.0-SNAPSHOT",
    scalaVersion := "2.9.0-1"
  )

  lazy val akka = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Unidoc.settings ++ rstdocSettings ++ Seq(
      parallelExecution in GlobalScope := false,
      Unidoc.unidocExclude := Seq(samples.id, tutorials.id),
      rstdocDirectory <<= baseDirectory / "akka-docs"
    ),
    aggregate = Seq(actor, testkit, actorTests, stm, http, remote, slf4j, camel, camelTyped, samples, tutorials)
    //aggregate = Seq(actor, testkit, actorTests, stm, http, slf4j, cluster, mailboxes, camel, camelTyped, samples, tutorials)
  )

  lazy val actor = Project(
    id = "akka-actor",
    base = file("akka-actor"),
    settings = defaultSettings ++ Seq(
      autoCompilerPlugins := true,
      libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % v) },
      scalacOptions += "-P:continuations:enable"
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
    dependencies = Seq(testkit),
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
    dependencies = Seq(actor, testkit % "test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.stm
    )
  )

  lazy val remote = Project(
    id = "akka-remote",
    base = file("akka-remote"),
    dependencies = Seq(stm, actorTests % "test->test", testkit % "test"),
    settings = defaultSettings ++ multiJvmSettings ++ Seq(
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

  // lazy val cluster = Project(
  //   id = "akka-cluster",
  //   base = file("akka-cluster"),
  //   dependencies = Seq(stm, actorTests % "test->test", testkit % "test"),
  //   settings = defaultSettings ++ multiJvmSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.cluster,
  //     extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
  //       (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
  //     },
  //     scalatestOptions in MultiJvm := Seq("-r", "org.scalatest.akka.QuietReporter"),
  //     jvmOptions in MultiJvm := {
  //       if (getBoolean("sbt.log.noformat")) Seq("-Dakka.test.nocolor=true") else Nil
  //     },
  //     test in Test <<= (test in Test) dependsOn (test in MultiJvm)
  //   )
  // ) configs (MultiJvm)

  lazy val http = Project(
    id = "akka-http",
    base = file("akka-http"),
    dependencies = Seq(actor, testkit % "test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.http
    )
  )

  lazy val slf4j = Project(
    id = "akka-slf4j",
    base = file("akka-slf4j"),
    dependencies = Seq(actor, testkit % "test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.slf4j
    )
  )

  // lazy val mailboxes = Project(
  //   id = "akka-durable-mailboxes",
  //   base = file("akka-durable-mailboxes"),
  //   settings = parentSettings,
  //   aggregate = Seq(mailboxesCommon, beanstalkMailbox, fileMailbox, redisMailbox, zookeeperMailbox)
  //   // aggregate = Seq(mailboxesCommon, beanstalkMailbox, fileMailbox, redisMailbox, zookeeperMailbox, mongoMailbox)
  // )

  // lazy val mailboxesCommon = Project(
  //   id = "akka-mailboxes-common",
  //   base = file("akka-durable-mailboxes/akka-mailboxes-common"),
  //   dependencies = Seq(cluster),
  //   settings = defaultSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.mailboxes
  //   )
  // )

  // val testBeanstalkMailbox = SettingKey[Boolean]("test-beanstalk-mailbox")

  // lazy val beanstalkMailbox = Project(
  //   id = "akka-beanstalk-mailbox",
  //   base = file("akka-durable-mailboxes/akka-beanstalk-mailbox"),
  //   dependencies = Seq(mailboxesCommon % "compile;test->test"),
  //   settings = defaultSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.beanstalkMailbox,
  //     testBeanstalkMailbox := false,
  //     testOptions in Test <+= testBeanstalkMailbox map { test => Tests.Filter(s => test) }
  //   )
  // )

  // lazy val fileMailbox = Project(
  //   id = "akka-file-mailbox",
  //   base = file("akka-durable-mailboxes/akka-file-mailbox"),
  //   dependencies = Seq(mailboxesCommon % "compile;test->test", testkit % "test"),
  //   settings = defaultSettings
  // )

  // val testRedisMailbox = SettingKey[Boolean]("test-redis-mailbox")

  // lazy val redisMailbox = Project(
  //   id = "akka-redis-mailbox",
  //   base = file("akka-durable-mailboxes/akka-redis-mailbox"),
  //   dependencies = Seq(mailboxesCommon % "compile;test->test"),
  //   settings = defaultSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.redisMailbox,
  //     testRedisMailbox := false,
  //     testOptions in Test <+= testRedisMailbox map { test => Tests.Filter(s => test) }
  //   )
  // )

  // lazy val zookeeperMailbox = Project(
  //   id = "akka-zookeeper-mailbox",
  //   base = file("akka-durable-mailboxes/akka-zookeeper-mailbox"),
  //   dependencies = Seq(mailboxesCommon % "compile;test->test", testkit % "test"),
  //   settings = defaultSettings
  // )

  // val testMongoMailbox = SettingKey[Boolean]("test-mongo-mailbox")

  // lazy val mongoMailbox = Project(
  //   id = "akka-mongo-mailbox",
  //   base = file("akka-durable-mailboxes/akka-mongo-mailbox"),
  //   dependencies = Seq(mailboxesCommon % "compile;test->test"),
  //   settings = defaultSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.mongoMailbox,
  //     testMongoMailbox := false,
  //     testOptions in Test <+= testMongoMailbox map { test => Tests.Filter(s => test) }
  //   )
  // )

  lazy val camel = Project(
    id = "akka-camel",
    base = file("akka-camel"),
    dependencies = Seq(actor, slf4j, testkit % "test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.camel
    )
  )

  // can be merged back into akka-camel
  lazy val camelTyped = Project(
    id = "akka-camel-typed",
    base = file("akka-camel-typed"),
    dependencies = Seq(camel % "compile;test->test", testkit % "test"),
    settings = defaultSettings
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
  //   dependencies = Seq(cluster, http, slf4j, spring),
  //   settings = defaultSettings ++ Seq(
  //     libraryDependencies ++= Dependencies.kernel
  //   )
  // )

  lazy val samples = Project(
    id = "akka-samples",
    base = file("akka-samples"),
    settings = parentSettings,
    aggregate = Seq(fsmSample, camelSample)
  )

  // lazy val antsSample = Project(
  //   id = "akka-sample-ants",
  //   base = file("akka-samples/akka-sample-ants"),
  //   dependencies = Seq(stm),
  //   settings = defaultSettings
  // )

  // lazy val chatSample = Project(
  //   id = "akka-sample-chat",
  //   base = file("akka-samples/akka-sample-chat"),
  //   dependencies = Seq(cluster),
  //   settings = defaultSettings
  // )

  lazy val fsmSample = Project(
    id = "akka-sample-fsm",
    base = file("akka-samples/akka-sample-fsm"),
    dependencies = Seq(actor),
    settings = defaultSettings
  )

  lazy val camelSample = Project(
    id = "akka-sample-camel",
    base = file("akka-samples/akka-sample-camel"),
    dependencies = Seq(actor, camelTyped, testkit % "test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.sampleCamel
    )
  )

  // lazy val helloSample = Project(
  //   id = "akka-sample-hello",
  //   base = file("akka-samples/akka-sample-hello"),
  //   dependencies = Seq(kernel),
  //   settings = defaultSettings
  // )

  // lazy val remoteSample = Project(
  //   id = "akka-sample-remote",
  //   base = file("akka-samples/akka-sample-remote"),
  //   dependencies = Seq(cluster),
  //   settings = defaultSettings
  // )

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

  // Settings

  override lazy val settings = super.settings ++ buildSettings ++ Publish.versionSettings

  lazy val baseSettings = Defaults.defaultSettings ++ Publish.settings

  lazy val parentSettings = baseSettings ++ Seq(
    publishArtifact in Compile := false
  )

  val testExcludes = SettingKey[Seq[String]]("test-excludes")

  def akkaTestExcludes: Seq[String] = {
    val exclude = System.getProperty("akka.test.exclude", "")
    if (exclude.isEmpty) Seq.empty else exclude.split(",").toSeq
  }

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

    // for excluding tests in jenkins builds (-Dakka.test.exclude=TimingSpec)
    testExcludes := akkaTestExcludes,
    testOptions in Test <++= testExcludes map { _.map(exclude => Tests.Filter(test => !test.contains(exclude))) },

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
      if (exitCode != 0) error("Failed to build docs.")
      s.log.info("Done building docs.")
      dir
    }
  }
}

// Dependencies

object Dependencies {
  import Dependency._

  val testkit = Seq(Test.scalatest)

  val actorTests = Seq(
    Test.junit, Test.scalatest, Test.multiverse, Test.commonsMath, Test.mockito,
    Test.scalacheck, protobuf, jacksonMapper, sjson
  )

  val stm = Seq(multiverse, Test.junit, Test.scalatest)

  val cluster = Seq(
    bookkeeper, commonsCodec, commonsIo, guice, h2Lzf, jacksonCore, jacksonMapper, log4j, netty,
    protobuf, sjson, zkClient, zookeeper, zookeeperLock, Test.junit, Test.scalatest
  )

  val http = Seq(
    jsr250, Provided.javaxServlet, Provided.jetty, Provided.jerseyServer, jsr311, commonsCodec,
    Test.junit, Test.scalatest, Test.mockito
  )

  val slf4j = Seq(slf4jApi)

  val mailboxes = Seq(Test.scalatest)

  val beanstalkMailbox = Seq(beanstalk)

  val redisMailbox = Seq(redis)

  val mongoMailbox = Seq(mongoAsync, twttrUtilCore)

  val camel = Seq(camelCore, Test.junit, Test.scalatest, Test.logback)

  val spring = Seq(springBeans, springContext, camelSpring, Test.junit, Test.scalatest)

  val kernel = Seq(
    jettyUtil, jettyXml, jettyServlet, jerseyCore, jerseyJson, jerseyScala,
    jacksonCore, staxApi, Provided.jerseyServer
  )

  // TODO: resolve Jetty version conflict
  val sampleCamel = Seq(camelCore, camelSpring, commonsCodec, Runtime.camelJms, Runtime.activemq, Runtime.springJms,
    Test.junit, Test.scalatest, Test.logback)
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
    val Slf4j        = "1.6.0"
    val Spring       = "3.0.5.RELEASE"
    val Zookeeper    = "3.4.0"
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
  val jerseyCore    = "com.sun.jersey"              % "jersey-core"            % V.Jersey     // CDDL v1
  val jerseyJson    = "com.sun.jersey"              % "jersey-json"            % V.Jersey     // CDDL v1
  val jerseyScala   = "com.sun.jersey.contribs"     % "jersey-scala"           % V.Jersey     // CDDL v1
  val jettyUtil     = "org.eclipse.jetty"           % "jetty-util"             % V.Jetty      // Eclipse license
  val jettyXml      = "org.eclipse.jetty"           % "jetty-xml"              % V.Jetty      // Eclipse license
  val jettyServlet  = "org.eclipse.jetty"           % "jetty-servlet"          % V.Jetty      // Eclipse license
  val jsr250        = "javax.annotation"            % "jsr250-api"             % "1.0"        // CDDL v1
  val jsr311        = "javax.ws.rs"                 % "jsr311-api"             % "1.1"        // CDDL v1
  val log4j         = "log4j"                       % "log4j"                  % "1.2.15"     // ApacheV2
  val mongoAsync    = "com.mongodb.async"           % "mongo-driver_2.9.0-1"   % "0.2.7"      //ApacheV2
  val multiverse    = "org.multiverse"              % "multiverse-alpha"       % V.Multiverse // ApacheV2
  val netty         = "org.jboss.netty"             % "netty"                  % V.Netty      // ApacheV2
  val osgi          = "org.osgi"                    % "org.osgi.core"          % "4.2.0"      // ApacheV2
  val protobuf      = "com.google.protobuf"         % "protobuf-java"          % V.Protobuf   // New BSD
  val redis         = "net.debasishg"               % "redisclient_2.9.0"      % "2.3.1"      // ApacheV2
  val sjson         = "net.debasishg"               % "sjson_2.9.0"            % "0.11"       // ApacheV2
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
    val javaxServlet = "org.apache.geronimo.specs" % "geronimo-servlet_3.0_spec" % "1.0" % "provided" //CDDL v1
    val jerseyServer = "com.sun.jersey"    % "jersey-server" % V.Jersey       % "provided" // CDDL v1
    val jetty        = "org.eclipse.jetty" % "jetty-server"  % V.Jetty        % "provided" // Eclipse license
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
    val scalatest   = "org.scalatest"           % "scalatest_2.9.0"     % V.Scalatest  % "test" // ApacheV2
    val scalacheck  = "org.scala-tools.testing" % "scalacheck_2.9.0"    % "1.9"        % "test" // New BSD
    val sjsonTest   = "net.debasishg"          %% "sjson"               % "0.11"       % "test" // ApacheV2
  }
}
