/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{ MultiJvm, extraOptions, jvmOptions, scalatestOptions, multiNodeExecuteTests, multiNodeJavaName, multiNodeHostsFileName, multiNodeTargetDirName }
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbtosgi.OsgiPlugin.{ OsgiKeys, osgiSettings }
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.{ enableOutput, generatePdf, generatedPdf, generateEpub, generatedEpub, sphinxInputs, sphinxPackages, Sphinx }
import com.typesafe.sbt.preprocess.Preprocess.{ preprocess, preprocessExts, preprocessVars, simplePreprocess }
import ls.Plugin.{ lsSettings, LsKeys }
import java.lang.Boolean.getBoolean
import sbt.Tests
import LsKeys.{ lsync, docsUrl => lsDocsUrl, tags => lsTags }
import java.io.{PrintWriter, InputStreamReader, FileInputStream, File}
import java.nio.charset.Charset
import java.util.Properties
import annotation.tailrec

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  // Load system properties from a file to make configuration from Jenkins easier
  loadSystemProperties("project/akka-build.properties")

  val enableMiMa = false

  lazy val buildSettings = Seq(
    organization := "com.typesafe.akka",
    version      := "2.2-SNAPSHOT",
    // Also change ScalaVersion in akka-sbt-plugin/sample/project/Build.scala
    scalaVersion := System.getProperty("akka.scalaVersion", "2.10.0")
  )

  lazy val akka = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Release.settings ++ Unidoc.settings ++ Publish.versionSettings ++
      SphinxSupport.settings ++ Dist.settings ++ mimaSettings ++ unidocScaladocSettings ++ Seq(
      testMailbox in GlobalScope := System.getProperty("akka.testMailbox", "false").toBoolean,
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Publish.defaultPublishTo in ThisBuild <<= crossTarget / "repository",
      Unidoc.unidocExclude := Seq(samples.id),
      Dist.distExclude := Seq(actorTests.id, akkaSbtPlugin.id, docs.id, samples.id, osgi.id, osgiAries.id),
      initialCommands in ThisBuild :=
        """|import language.postfixOps
           |import akka.actor._
           |import ActorDSL._
           |import scala.concurrent._
           |import com.typesafe.config.ConfigFactory
           |import scala.concurrent.duration._
           |import akka.util.Timeout
           |val config = ConfigFactory.parseString("akka.stdout-loglevel=INFO,akka.loglevel=DEBUG,pinned{type=PinnedDispatcher,executor=thread-pool-executor,throughput=1000}")
           |val remoteConfig = ConfigFactory.parseString("akka.remote.netty{port=0,use-dispatcher-for-io=akka.actor.default-dispatcher,execution-pool-size=0},akka.actor.provider=akka.remote.RemoteActorRefProvider").withFallback(config)
           |var system: ActorSystem = null
           |implicit def _system = system
           |def startSystem(remoting: Boolean = false) { system = ActorSystem("repl", if(remoting) remoteConfig else config); println("don’t forget to system.shutdown()!") }
           |implicit def ec = system.dispatcher
           |implicit val timeout = Timeout(5 seconds)
           |""".stripMargin,
      initialCommands in Test in ThisBuild += "import akka.testkit._",
      // generate online version of docs
      sphinxInputs in Sphinx <<= sphinxInputs in Sphinx in LocalProject(docs.id) map { inputs => inputs.copy(tags = inputs.tags :+ "online") },
      // don't regenerate the pdf, just reuse the akka-docs version
      generatedPdf in Sphinx <<= generatedPdf in Sphinx in LocalProject(docs.id) map identity,
      generatedEpub in Sphinx <<= generatedEpub in Sphinx in LocalProject(docs.id) map identity
    ),
    aggregate = Seq(actor, testkit, actorTests, dataflow, remote, remoteTests, camel, cluster, slf4j, agent, transactor, mailboxes, zeroMQ, kernel, akkaSbtPlugin, osgi, osgiAries, docs, contrib, samples, channels, channelsTests)
  )

  lazy val actor = Project(
    id = "akka-actor",
    base = file("akka-actor"),
    settings = defaultSettings ++ scaladocSettings ++ Seq(
      autoCompilerPlugins := true,
      // to fix scaladoc generation
      fullClasspath in doc in Compile <<= fullClasspath in Compile,
      libraryDependencies ++= Dependencies.actor,
      previousArtifact := akkaPreviousArtifact("akka-actor")
    )
  )

  def cpsPlugin = Seq(
    libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % v) },
    scalacOptions += "-P:continuations:enable"
  )

  lazy val dataflow = Project(
    id = "akka-dataflow",
    base = file("akka-dataflow"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ scaladocSettings  ++ OSGi.dataflow ++ cpsPlugin
  )

  lazy val testkit = Project(
    id = "akka-testkit",
    base = file("akka-testkit"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.testkit ++ Seq(
      libraryDependencies ++= Dependencies.testkit,
      initialCommands += "import akka.testkit._",
      previousArtifact := akkaPreviousArtifact("akka-testkit")
    )
  )

  lazy val actorTests = Project(
    id = "akka-actor-tests",
    base = file("akka-actor-tests"),
    dependencies = Seq(testkit % "compile;test->test"),
    settings = defaultSettings ++ scaladocSettings  ++ Seq(
      autoCompilerPlugins := true,
      libraryDependencies ++= Dependencies.actorTests
    )
  )

  lazy val remote = Project(
    id = "akka-remote",
    base = file("akka-remote"),
    dependencies = Seq(actor, actorTests % "test->test", testkit % "test->test"),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.remote ++ Seq(
      libraryDependencies ++= Dependencies.remote,
      // disable parallel tests
      parallelExecution in Test := false
    )
  )

  lazy val remoteTests = Project(
    id = "akka-remote-tests-experimental",
    base = file("akka-remote-tests"),
    dependencies = Seq(remote, actorTests % "test->test", testkit),
    settings = defaultSettings ++ scaladocSettings ++ multiJvmSettings ++ experimentalSettings ++ Seq(
      libraryDependencies ++= Dependencies.remoteTests,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := defaultMultiJvmScalatestOptions,
      previousArtifact := akkaPreviousArtifact("akka-remote")
    )
  ) configs (MultiJvm)

  lazy val cluster = Project(
    id = "akka-cluster-experimental",
    base = file("akka-cluster"),
    dependencies = Seq(remote, remoteTests % "test->test" , testkit % "test->test"),
    settings = defaultSettings ++ scaladocSettings ++ multiJvmSettings ++ OSGi.cluster ++ experimentalSettings ++ Seq(
      libraryDependencies ++= Dependencies.cluster,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      },
      scalatestOptions in MultiJvm := defaultMultiJvmScalatestOptions,
      previousArtifact := akkaPreviousArtifact("akka-remote")
    )
  ) configs (MultiJvm)

  lazy val slf4j = Project(
    id = "akka-slf4j",
    base = file("akka-slf4j"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.slf4j ++ Seq(
      libraryDependencies ++= Dependencies.slf4j
    )
  )

  lazy val agent = Project(
    id = "akka-agent",
    base = file("akka-agent"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.agent ++ Seq(
      libraryDependencies ++= Dependencies.agent,
      previousArtifact := akkaPreviousArtifact("akka-agent")
    )
  )

  lazy val transactor = Project(
    id = "akka-transactor",
    base = file("akka-transactor"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.transactor ++ Seq(
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
    settings = defaultSettings ++ scaladocSettings ++ OSGi.mailboxesCommon ++ Seq(
      libraryDependencies ++= Dependencies.mailboxes,
      previousArtifact := akkaPreviousArtifact("akka-mailboxes-common"),
      publishArtifact in Test := true
    )
  )

  lazy val fileMailbox = Project(
    id = "akka-file-mailbox",
    base = file("akka-durable-mailboxes/akka-file-mailbox"),
    dependencies = Seq(mailboxesCommon % "compile;test->test", testkit % "test"),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.fileMailbox ++ Seq(
      libraryDependencies ++= Dependencies.fileMailbox,
      previousArtifact := akkaPreviousArtifact("akka-file-mailbox")
    )
  )

  lazy val zeroMQ = Project(
    id = "akka-zeromq",
    base = file("akka-zeromq"),
    dependencies = Seq(actor, testkit % "test;test->test"),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.zeroMQ ++ Seq(
      libraryDependencies ++= Dependencies.zeroMQ,
      previousArtifact := akkaPreviousArtifact("akka-zeromq")
    )
  )

  lazy val kernel = Project(
    id = "akka-kernel",
    base = file("akka-kernel"),
    dependencies = Seq(actor, testkit % "test->test"),
    settings = defaultSettings ++ scaladocSettings ++ Seq(
      libraryDependencies ++= Dependencies.kernel,
      previousArtifact := akkaPreviousArtifact("akka-kernel")
    )
  )

  lazy val camel = Project(
     id = "akka-camel",
     base = file("akka-camel"),
     dependencies = Seq(actor, slf4j, testkit % "test->test"),
     settings = defaultSettings ++ scaladocSettings ++ OSGi.camel ++ Seq(
       libraryDependencies ++= Dependencies.camel
     )
  )

  val ActorReferenceCopyTask = TaskKey[Int]("hello", "Copy reference.conf from akka modules to akka-osgi")

  val ActorReferenceCopyAction = (streams)  map { (s) =>
    s.log.debug("Copying of the akka-actor reference.conf in akka-osgi")
    (file("akka-osgi/src/main/resources")).mkdir()
    if ((file("akka-osgi/src/main/resources/reference.conf")).exists){(file("akka-osgi/src/main/resources/reference.conf")).delete()}
    val projectReferencesToCopy = for (project <- projects.filter(p => !p.id.contains("test") && !p.id.contains("sample"))
      if (file(project.base+"/src/main/resources/reference.conf")).exists())  yield project

    val referencesFileToInclude = projectReferencesToCopy.map(project => {
      copyFile(project.base+"/src/main/resources/reference.conf", "akka-osgi/src/main/resources/"+project.id+".conf")
      "include \""+ project.id +".conf\""
      })

    val writer = new PrintWriter(file("akka-osgi/src/main/resources/reference.conf" ))
    writer.write(referencesFileToInclude.mkString("\n"))
    writer.close()
    s.log.info("Akka module reference.conf copied in akka-osgi")
    projects.size
  }



  lazy val osgi = Project(
    id = "akka-osgi",
    base = file("akka-osgi"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.osgi ++ Seq(
      libraryDependencies ++= Dependencies.osgi,
      ActorReferenceCopyTask in Compile <<= ActorReferenceCopyAction ,
      cleanFiles <+= baseDirectory { base => base / "src/main/resources" } ,
      compile in Compile <<= compile in Compile dependsOn (ActorReferenceCopyTask in Compile),
      parallelExecution in Test := false
    )
  )

  lazy val osgiAries = Project(
    id = "akka-osgi-aries",
    base = file("akka-osgi-aries"),
    dependencies = Seq(osgi % "compile;test->test"),
    settings = defaultSettings ++ scaladocSettings ++ OSGi.osgiAries ++ Seq(
      libraryDependencies ++= Dependencies.osgiAries,
      parallelExecution in Test := false
    )
  )

  lazy val akkaSbtPlugin = Project(
    id = "akka-sbt-plugin",
    base = file("akka-sbt-plugin"),
    settings = defaultSettings ++ Seq(
      sbtPlugin := true,
      publishMavenStyle := false, // SBT Plugins should be published as Ivy
      publishTo <<= Publish.akkaPluginPublishTo,
      scalacOptions in Compile := Seq("-encoding", "UTF-8", "-deprecation", "-unchecked"),
      scalaVersion := "2.9.2",
      scalaBinaryVersion <<= scalaVersion
    )
  )

  lazy val samples = Project(
    id = "akka-samples",
    base = file("akka-samples"),
    settings = parentSettings,
    aggregate = Seq(camelSample, fsmSample, helloSample, helloKernelSample, remoteSample, clusterSample, multiNodeSample)
  )

  lazy val camelSample = Project(
    id = "akka-sample-camel",
    base = file("akka-samples/akka-sample-camel"),
    dependencies = Seq(actor, camel),
    settings = sampleSettings ++ Seq(libraryDependencies ++= Dependencies.camelSample)
  )

  lazy val fsmSample = Project(
    id = "akka-sample-fsm",
    base = file("akka-samples/akka-sample-fsm"),
    dependencies = Seq(actor),
    settings = sampleSettings
  )

  lazy val helloSample = Project(
    id = "akka-sample-hello",
    base = file("akka-samples/akka-sample-hello"),
    dependencies = Seq(actor),
    settings = sampleSettings
  )

  lazy val helloKernelSample = Project(
    id = "akka-sample-hello-kernel",
    base = file("akka-samples/akka-sample-hello-kernel"),
    dependencies = Seq(kernel),
    settings = sampleSettings
  )

  lazy val remoteSample = Project(
    id = "akka-sample-remote",
    base = file("akka-samples/akka-sample-remote"),
    dependencies = Seq(actor, remote, kernel),
    settings = sampleSettings
  )

  lazy val clusterSample = Project(
    id = "akka-sample-cluster-experimental",
    base = file("akka-samples/akka-sample-cluster"),
    dependencies = Seq(cluster, contrib, remoteTests % "test", testkit % "test"),
    settings = sampleSettings ++ multiJvmSettings ++ experimentalSettings ++ Seq(
      // sigar is in Typesafe repo
      resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
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

  lazy val multiNodeSample = Project(
    id = "akka-sample-multi-node-experimental",
    base = file("akka-samples/akka-sample-multi-node"),
    dependencies = Seq(remoteTests % "test", testkit % "test"),
    settings = sampleSettings ++ multiJvmSettings ++ experimentalSettings ++ Seq(
      libraryDependencies ++= Dependencies.multiNodeSample,
      // disable parallel tests
      parallelExecution in Test := false,
      extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
        (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
      }
    )
  ) configs (MultiJvm)

  lazy val docs = Project(
    id = "akka-docs",
    base = file("akka-docs"),
    dependencies = Seq(actor, testkit % "test->test", mailboxesCommon % "compile;test->test", channels,
      remote, cluster, slf4j, agent, dataflow, transactor, fileMailbox, zeroMQ, camel, osgi, osgiAries),
    settings = defaultSettings ++ site.settings ++ site.sphinxSupport() ++ site.publishSite ++ sphinxPreprocessing ++ cpsPlugin ++ Seq(
      sourceDirectory in Sphinx <<= baseDirectory / "rst",
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
      unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test,
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")
    )
  )

  lazy val contrib = Project(
    id = "akka-contrib",
    base = file("akka-contrib"),
    dependencies = Seq(remote, remoteTests % "compile;test->test", cluster),
    settings = defaultSettings ++ multiJvmSettings ++ Seq(
      libraryDependencies ++= Dependencies.contrib,
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v"),
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

  lazy val channels = Project(
    id = "akka-channels-experimental",
    base = file("akka-channels"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ Seq(
      libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _)
    )
  )

  lazy val channelsTests = Project(
    id = "akka-channels-tests",
    base = file("akka-channels-tests"),
    dependencies = Seq(channels, testkit % "compile;test->test"),
    settings = defaultSettings ++ Seq(
      libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-compiler" % _)
    )
  )

  // Settings

  override lazy val settings =
    super.settings ++
    buildSettings ++
    Seq(
      shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
    )

  lazy val baseSettings = Defaults.defaultSettings ++ Publish.settings

  lazy val parentSettings = baseSettings ++ Seq(
    publishArtifact in Compile := false
  )

  lazy val sampleSettings = defaultSettings ++ Seq(
    publishArtifact in (Compile, packageBin) := false
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
    val MultinodeJvmArgs = "multinode\\.(D|X)(.*)".r
    val akkaProperties = System.getProperties.propertyNames.asScala.toList.collect {
      case MultinodeJvmArgs(a, b) =>
        val value = System.getProperty("multinode." + a + b)
        "-" + a + b + (if (value == "") "" else "=" + value)
      case key: String if key.startsWith("multinode.") => "-D" + key + "=" + System.getProperty(key)
      case key: String if key.startsWith("akka.") => "-D" + key + "=" + System.getProperty(key)
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

  lazy val defaultSettings = baseSettings ++ formatSettings ++ mimaSettings ++ lsSettings ++ Seq(
    // compile options
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Ywarn-adapted-args"),
    javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation"),

    // if changing this between binary and full, also change at the bottom of akka-sbt-plugin/sample/project/Build.scala
    crossVersion := CrossVersion.binary,

    ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet,

    description in lsync := "Akka is the platform for the next generation of event-driven, scalable and fault-tolerant architectures on the JVM.",
    homepage in lsync := Some(url("http://akka.io")),
    lsTags in lsync := Seq("actors", "stm", "concurrency", "distributed", "fault-tolerance", "scala", "java", "futures", "dataflow", "remoting"),
    lsDocsUrl in lsync := Some(url("http://akka.io/docs")),
    licenses in lsync := Seq(("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0.html"))),
    externalResolvers in lsync := Seq("Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases"),

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
    testOptions in Test += Tests.Argument("-oDF")
  )

  // preprocessing settings for sphinx
  lazy val sphinxPreprocessing = inConfig(Sphinx)(Seq(
    target in preprocess <<= baseDirectory / "rst_preprocessed",
    preprocessExts := Set("rst", "py"),
    // customization of sphinx @<key>@ replacements, add to all sphinx-using projects
    // add additional replacements here
    preprocessVars <<= (scalaVersion, version) { (s, v) =>
      val isSnapshot = v.endsWith("SNAPSHOT")
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
        "github" -> "http://github.com/akka/akka/tree/%s".format((if (isSnapshot) "master" else "v" + v))
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

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ inConfig(MultiJvm)(SbtScalariform.scalariformSettings) ++ Seq(
    jvmOptions in MultiJvm := defaultMultiJvmOptions,
    compileInputs in MultiJvm <<= (compileInputs in MultiJvm) dependsOn (ScalariformKeys.format in MultiJvm),
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    ScalariformKeys.preferences in MultiJvm := formattingPreferences) ++
    Option(System.getProperty("akka.test.multi-node.hostsFileName")).map(x => Seq(multiNodeHostsFileName in MultiJvm := x)).getOrElse(Seq.empty) ++
    Option(System.getProperty("akka.test.multi-node.java")).map(x => Seq(multiNodeJavaName in MultiJvm := x)).getOrElse(Seq.empty) ++
    Option(System.getProperty("akka.test.multi-node.targetDirName")).map(x => Seq(multiNodeTargetDirName in MultiJvm := x)).getOrElse(Seq.empty) ++
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

  lazy val scaladocDiagramsEnabled = System.getProperty("akka.scaladoc.diagrams", "true").toBoolean
  lazy val scaladocOptions = List("-implicits") ::: (if (scaladocDiagramsEnabled) List("-diagrams") else Nil)

  lazy val scaladocSettings: Seq[sbt.Setting[_]]= {
    Seq(scalacOptions in (Compile, doc) ++= scaladocOptions) ++
      (if (scaladocDiagramsEnabled)
        Seq(doc in Compile ~= scaladocVerifier)
       else Seq.empty)
  }

  lazy val unidocScaladocSettings: Seq[sbt.Setting[_]]= {
    Seq(scalacOptions in doc ++= scaladocOptions) ++
      (if (scaladocDiagramsEnabled)
        Seq(Unidoc.unidoc ~= scaladocVerifier)
      else Seq.empty)
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

  lazy val mimaSettings = mimaDefaultSettings ++ Seq(
    // MiMa
    previousArtifact := None
  )

  def akkaPreviousArtifact(id: String, organization: String = "com.typesafe.akka", version: String = "2.0"): Option[sbt.ModuleID] =
    if (enableMiMa) Some(organization % id % version) // the artifact to compare binary compatibility with
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

  def copyFile(source: String, sink: String){
    val src = new java.io.File(source)
    val dest = new java.io.File(sink)
    new java.io.FileOutputStream(dest) getChannel() transferFrom(
      new java.io.FileInputStream(src) getChannel, 0, Long.MaxValue )
  }

  // OSGi settings

  object OSGi {

    //akka-actor is wrapped into akka-osgi to simplify OSGi deployement.

    val agent = exports(Seq("akka.agent.*"))

    val camel = exports(Seq("akka.camel.*"))

    val cluster = exports(Seq("akka.cluster.*"))

    val fileMailbox = exports(Seq("akka.actor.mailbox.filebased.*"))

    val mailboxesCommon = exports(Seq("akka.actor.mailbox.*"), imports = Seq(protobufImport()))

    val osgi = osgiSettings ++ Seq(
      OsgiKeys.exportPackage := Seq("akka*"), //exporting akka packages enforces bnd to aggregate akka-actor packages in the bundle
      OsgiKeys.privatePackage := Seq("akka.osgi.impl"),
      //akka-actor packages are not imported, as contained in the CP
      OsgiKeys.importPackage := (osgiOptionalImports map optionalResolution) ++ Seq("!sun.misc", scalaImport(),configImport(), "*") 
     )

    val osgiAries = exports() ++ Seq(OsgiKeys.privatePackage := Seq("akka.osgi.aries.*"))

    val remote = exports(Seq("akka.remote.*"), imports = Seq(protobufImport()))

    val slf4j = exports(Seq("akka.event.slf4j.*"))

    val dataflow = exports(Seq("akka.dataflow.*"))

    val transactor = exports(Seq("akka.transactor.*"))

    val testkit = exports(Seq("akka.testkit.*"))

    val zeroMQ = exports(Seq("akka.zeromq.*"), imports = Seq(protobufImport()) )

    val osgiOptionalImports = Seq("akka.remote",
      "akka.remote.transport.netty",
      "akka.remote.security.provider",
      "akka.remote.netty",
      "akka.remote.routing",
      "akka.remote.transport",
      "akka.remote.serialization",
      "akka.cluster",
      "akka.cluster.routing",
      "akka.transactor",
      "akka.agent",
      "akka.dataflow",
      "akka.actor.mailbox",
      "akka.camel.internal",
      "akka.camel.javaapi",
      "akka.camel",
      "akka.camel.internal.component",
      "akka.zeromq",
      "com.google.protobuf")

    def exports(packages: Seq[String] = Seq(), imports: Seq[String] = Nil) = osgiSettings ++ Seq(
      OsgiKeys.importPackage := imports ++ defaultImports,
      OsgiKeys.exportPackage := packages
    )
    def defaultImports = Seq("!sun.misc", akkaImport(), configImport(), scalaImport(), "*")
    def akkaImport(packageName: String = "akka.*") = "%s;version=\"[2.2,2.3)\"".format(packageName)
    def configImport(packageName: String = "com.typesafe.config.*") = "%s;version=\"[0.4.1,1.1.0)\"".format(packageName)
    def protobufImport(packageName: String = "com.google.protobuf.*") = "%s;version=\"[2.4.0,2.5.0)\"".format(packageName)
    def scalaImport(packageName: String = "scala.*") = "%s;version=\"[2.10,2.11)\"".format(packageName)
    def optionalResolution(packageName: String) = "%s;resolution:=optional".format(packageName)
  }
}

// Dependencies

object Dependencies {

  object Compile {
    // Compile
    val camelCore     = "org.apache.camel"            % "camel-core"                   % "2.10.3" exclude("org.slf4j", "slf4j-api") // ApacheV2

    val config        = "com.typesafe"                % "config"                       % "1.0.0"       // ApacheV2
    val netty         = "io.netty"                    % "netty"                        % "3.6.1.Final" // ApacheV2
    val protobuf      = "com.google.protobuf"         % "protobuf-java"                % "2.4.1"       // New BSD
    val scalaStm      = "org.scala-stm"              %% "scala-stm"                    % "0.7"         // Modified BSD (Scala)

    val slf4jApi      = "org.slf4j"                   % "slf4j-api"                    % "1.7.2"       // MIT
    val zeroMQClient  = "org.zeromq"                 %% "zeromq-scala-binding"         % "0.0.7"       // ApacheV2
    val uncommonsMath = "org.uncommons.maths"         % "uncommons-maths"              % "1.2.2a" exclude("jfree", "jcommon") exclude("jfree", "jfreechart")      // ApacheV2
    val ariesBlueprint = "org.apache.aries.blueprint" % "org.apache.aries.blueprint"   % "0.3.2"       // ApacheV2
    val osgiCore      = "org.osgi"                    % "org.osgi.core"                % "4.2.0"       // ApacheV2
    val osgiCompendium= "org.osgi"                    % "org.osgi.compendium"          % "4.2.0"       // ApacheV2

    // Camel Sample
    val camelJetty  = "org.apache.camel"              % "camel-jetty"                  % camelCore.revision // ApacheV2

    // Cluster Sample
    val sigar       = "org.hyperic"                   % "sigar"                        % "1.6.4"            // ApacheV2

    // Test

    object Test {
      val commonsMath  = "org.apache.commons"          % "commons-math"                 % "2.1"              % "test" // ApacheV2
      val commonsIo    = "commons-io"                  % "commons-io"                   % "2.0.1"            % "test" // ApacheV2
      val commonsCodec = "commons-codec"               % "commons-codec"                % "1.7"              % "test" // ApacheV2
      val junit        = "junit"                       % "junit"                        % "4.10"             % "test" // Common Public License 1.0
      val logback      = "ch.qos.logback"              % "logback-classic"              % "1.0.7"            % "test" // EPL 1.0 / LGPL 2.1
      val mockito      = "org.mockito"                 % "mockito-all"                  % "1.8.1"            % "test" // MIT
      // changing the scalatest dependency must be reflected in akka-docs/rst/dev/multi-jvm-testing.rst
      val scalatest    = "org.scalatest"              %% "scalatest"                    % "1.9.1"            % "test" // ApacheV2
      val scalacheck   = "org.scalacheck"             %% "scalacheck"                   % "1.10.0"           % "test" // New BSD
      val ariesProxy   = "org.apache.aries.proxy"      % "org.apache.aries.proxy.impl"  % "0.3"              % "test" // ApacheV2
      val pojosr       = "com.googlecode.pojosr"       % "de.kalpatec.pojosr.framework" % "0.1.4"            % "test" // ApacheV2
      val tinybundles  = "org.ops4j.pax.tinybundles"   % "tinybundles"                  % "1.0.0"            % "test" // ApacheV2
      val log4j        = "log4j"                       % "log4j"                        % "1.2.14"           % "test" // ApacheV2
      val junitIntf    = "com.novocode"                % "junit-interface"              % "0.8"              % "test" // MIT
    }
  }

  import Compile._

  val actor = Seq(config)

  val testkit = Seq(Test.junit, Test.scalatest)

  val actorTests = Seq(Test.junit, Test.scalatest, Test.commonsCodec, Test.commonsMath, Test.mockito, Test.scalacheck, protobuf)

  val remote = Seq(netty, protobuf, uncommonsMath, Test.junit, Test.scalatest)

  val remoteTests = Seq(Test.junit, Test.scalatest)

  val cluster = Seq(Test.junit, Test.scalatest)

  val slf4j = Seq(slf4jApi, Test.logback)

  val agent = Seq(scalaStm, Test.scalatest, Test.junit)

  val transactor = Seq(scalaStm, Test.scalatest, Test.junit)

  val mailboxes = Seq(Test.scalatest, Test.junit)

  val fileMailbox = Seq(Test.commonsIo, Test.scalatest, Test.junit)

  val kernel = Seq(Test.scalatest, Test.junit)

  val camel = Seq(camelCore, Test.scalatest, Test.junit, Test.mockito, Test.logback, Test.commonsIo)

  val camelSample = Seq(camelJetty)

  val osgi = Seq(osgiCore, osgiCompendium, Test.logback, Test.commonsIo, Test.pojosr, Test.tinybundles, Test.scalatest, Test.junit)

  val osgiAries = Seq(osgiCore, ariesBlueprint, Test.ariesProxy)

  val docs = Seq(Test.scalatest, Test.junit, Test.junitIntf)

  val zeroMQ = Seq(protobuf, zeroMQClient, Test.scalatest, Test.junit)

  val clusterSample = Seq(Test.scalatest, sigar)

  val contrib = Seq(Test.junitIntf)

  val multiNodeSample = Seq(Test.scalatest)
}
