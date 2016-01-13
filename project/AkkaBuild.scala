/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Properties

import akka.TestExtras.JUnitFileReporting
import com.typesafe.sbt.S3Plugin.S3
import com.typesafe.sbt.S3Plugin.s3Settings
import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import sbt.Keys._
import sbt._
import sbtunidoc.Plugin.ScalaUnidoc
import sbtunidoc.Plugin.JavaUnidoc
import sbtunidoc.Plugin.UnidocKeys._

object AkkaBuild extends Build {
  System.setProperty("akka.mode", "test") // Is there better place for this?

  // Load system properties from a file to make configuration from Jenkins easier
  loadSystemProperties("project/akka-build.properties")

  override def buildLoaders = BuildLoader.transform(Sample.buildTransformer) :: Nil

  val enableMiMa = true

  val parallelExecutionByDefault = false // TODO: enable this once we're sure it doesn not break things

  lazy val buildSettings = Dependencies.Versions ++ Seq(
    organization        := "com.typesafe.akka",
    version             := "2.4-SNAPSHOT"
  )

  lazy val root = Project(
    id = "akka",
    base = file("."),
    settings = parentSettings ++ Release.settings ++
      SphinxDoc.akkaSettings ++ Dist.settings ++ s3Settings ++
      UnidocRoot.akkaSettings ++
      Protobuf.settings ++ Seq(
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", parallelExecutionByDefault.toString).toBoolean,
      Dist.distExclude := Seq(actorTests.id, docs.id, samples.id, osgi.id),

      // FIXME problem with scalaunidoc:doc, there must be a better way
      unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(protobuf, samples,
        sampleCamelJava, sampleCamelScala, sampleClusterJava, sampleClusterScala, sampleFsmScala, sampleFsmJavaLambda,
        sampleMainJava, sampleMainScala, sampleMainJavaLambda, sampleMultiNodeScala,
        samplePersistenceJava, samplePersistenceScala, samplePersistenceJavaLambda,
        sampleRemoteJava, sampleRemoteScala, sampleSupervisionJavaLambda,
        sampleDistributedDataScala, sampleDistributedDataJava),

      S3.host in S3.upload := "downloads.typesafe.com.s3.amazonaws.com",
      S3.progress in S3.upload := true,
      mappings in S3.upload <<= (Release.releaseDirectory, version) map { (d, v) =>
        val downloads = d / "downloads"
        val archivesPathFinder = downloads * s"*$v.zip"
        archivesPathFinder.get.map(file => (file -> ("akka/" + file.getName)))
      }
    ),
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel,
      cluster, clusterMetrics, clusterTools, clusterSharding, distributedData,
      slf4j, agent, persistence, persistenceQuery, persistenceTck, kernel, osgi, docs, contrib, samples, multiNodeTestkit, benchJmh, typed, protobuf,
      // streamAndHttp, // does not seem to work
      stream, streamTestkit, streamTests, streamTestsTck,
      httpCore, http, httpSprayJson, httpXml, httpJackson, httpTests, httpTestkit,
      docsDev // TODO merge with `docs`
    )
  )

  lazy val akkaScalaNightly = Project(
    id = "akka-scala-nightly",
    base = file("akka-scala-nightly"),
    // remove dependencies that we have to build ourselves (Scala STM)
    // samples don't work with dbuild right now
    aggregate = Seq(actor, testkit, actorTests, remote, remoteTests, camel,
      cluster, clusterMetrics, clusterTools, clusterSharding, distributedData,
      slf4j, persistence, persistenceQuery, persistenceTck, kernel, osgi, contrib, multiNodeTestkit, benchJmh, typed, protobuf,
      // streamAndHttp, // does not seem to work
      stream, streamTestkit, streamTests, streamTestsTck,
      httpCore, http, httpSprayJson, httpXml, httpJackson, httpTests, httpTestkit,
      docsDev // TODO merge with `docs`
    )
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
    dependencies = Seq(
      actor,
      http, stream, streamTests,
      persistence, distributedData,
      testkit
    ).map(_ % "compile;compile->test;provided->provided")
  ).disablePlugins(ValidatePullRequest)

  lazy val protobuf = Project(
    id = "akka-protobuf",
    base = file("akka-protobuf")
  )

  lazy val remote = Project(
    id = "akka-remote",
    base = file("akka-remote"),
    dependencies = Seq(actor, actorTests % "test->test", testkit % "test->test", protobuf)
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

  lazy val clusterTools = Project(
    id = "akka-cluster-tools",
    base = file("akka-cluster-tools"),
    dependencies = Seq(cluster % "compile->compile;test->test;multi-jvm->multi-jvm")
  ) configs (MultiJvm)

  lazy val clusterSharding = Project(
    id = "akka-cluster-sharding",
    base = file("akka-cluster-sharding"),
    // TODO akka-distributed-data dependency should be provided in pom.xml artifact.
    //      If I only use "provided" here it works, but then we can't run tests.
    //      Scope "test" is alright in the pom.xml, but would have been nicer with
    //      provided.
    dependencies = Seq(cluster % "compile->compile;test->test;multi-jvm->multi-jvm",
        persistence % "compile;test->provided", distributedData % "provided;test", clusterTools)
  ) configs (MultiJvm)

  lazy val distributedData = Project(
    id = "akka-distributed-data-experimental",
    base = file("akka-distributed-data"),
    dependencies = Seq(cluster % "compile->compile;test->test;multi-jvm->multi-jvm")
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
    id = "akka-persistence",
    base = file("akka-persistence"),
    dependencies = Seq(actor, remote % "test->test", testkit % "test->test", protobuf)
  )

  lazy val persistenceQuery = Project(
    id = "akka-persistence-query-experimental",
    base = file("akka-persistence-query"),
    dependencies = Seq(
      stream,
      persistence % "compile;provided->provided;test->test",
      testkit % "compile;test->test",
      streamTestkit % "compile;test->test")
  )

  lazy val persistenceTck = Project(
    id = "akka-persistence-tck",
    base = file("akka-persistence-tck"),
    dependencies = Seq(persistence % "compile;provided->provided;test->test", testkit % "compile;test->test")
  )

  lazy val streamAndHttp = Project(
    id = "akka-stream-and-http-experimental",
    base = file("akka-stream-and-http"),
    settings = parentSettings ++ Release.settings ++
      SphinxDoc.akkaSettings ++
      Dist.settings ++
      Protobuf.settings ++ Seq(
      Dist.distExclude := Seq(),
      //      testMailbox in GlobalScope := System.getProperty("akka.testMailbox", "false").toBoolean,
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", "false").toBoolean,
      Publish.defaultPublishTo in ThisBuild <<= crossTarget / "repository"
//     javacOptions in JavaDoc ++= Seq("-Xdoclint:none"), TODO likely still needed
      //      artifactName in packageDoc in JavaDoc := ((sv, mod, art) => "" + mod.name + "_" + sv.binary + "-" + mod.revision + "-javadoc.jar"),
      //      packageDoc in Compile <<= packageDoc in JavaDoc,

      //      // generate online version of docs
      //      sphinxInputs in Sphinx <<= sphinxInputs in Sphinx in LocalProject(docsDev.id) map { inputs => inputs.copy(tags = inputs.tags :+ "online") },
      //      // don't regenerate the pdf, just reuse the akka-docs version
      //      generatedPdf in Sphinx <<= generatedPdf in Sphinx in LocalProject(docsDev.id) map identity,
      //      generatedEpub in Sphinx <<= generatedEpub in Sphinx in LocalProject(docsDev.id) map identity,

      //      publishArtifact in packageSite := false
    ),
    aggregate = Seq(parsing, stream, streamTestkit, streamTests, streamTestsTck, httpParent)
  )

  lazy val httpParent = Project(
    id = "akka-http-parent-experimental",
    base = file("akka-http-parent"),
    settings = parentSettings,
    aggregate = Seq(
      httpCore,
      http,
      httpTestkit,
      httpTests,
      httpMarshallersScala, httpMarshallersJava
    )
  )

  lazy val httpCore = Project(
    id = "akka-http-core-experimental",
    base = file("akka-http-core"),
    dependencies = Seq(stream, parsing, streamTestkit % "test->test"),
    settings = defaultSettings
  )

  lazy val http = Project(
    id = "akka-http-experimental",
    base = file("akka-http"),
    dependencies = Seq(httpCore),
    settings =
      defaultSettings ++
        Seq(
          scalacOptions in Compile += "-language:_"
        )
  )

  lazy val streamTestkit = Project(
    id = "akka-stream-testkit",
    base = file("akka-stream-testkit"), // TODO that persistence dependency
    dependencies = Seq(stream, persistence % "compile;provided->provided;test->test", testkit % "compile;test->test"),
    settings = defaultSettings ++ experimentalSettings
  )

  lazy val httpTestkit = Project(
    id = "akka-http-testkit-experimental",
    base = file("akka-http-testkit"),
    dependencies = Seq(http, streamTestkit),
    settings =
      defaultSettings ++ Seq(
          scalacOptions in Compile  += "-language:_"
        )
  )

  lazy val httpTests = Project(
    id = "akka-http-tests-experimental",
    base = file("akka-http-tests"),
    dependencies = Seq(httpTestkit % "test", httpSprayJson, httpXml, httpJackson),
    settings =
      defaultSettings ++ Seq(
          publishArtifact := false,
          scalacOptions in Compile  += "-language:_",
          // test discovery is broken when sbt isn't run with a Java 8 compatible JVM, so we define a single
          // Suite where all tests need to be registered
          definedTests in Test := {
            def pseudoJUnitRunWithFingerprint =
              // we emulate a junit-interface fingerprint here which cannot be accessed statically
              new sbt.testing.AnnotatedFingerprint {
                def annotationName = "org.junit.runner.RunWith"
                def isModule = false
              }
            Seq(new TestDefinition("AllJavaTests", pseudoJUnitRunWithFingerprint, false, Array.empty))
          },
          // don't ignore Suites which is the default for the junit-interface
          testOptions += Tests.Argument(TestFrameworks.JUnit, "--ignore-runners="),
          mainClass in run in Test := Some("akka.http.javadsl.SimpleServerApp")
        )
  )

  lazy val httpMarshallersScala = Project(
    id = "akka-http-marshallers-scala-experimental",
    base = file("akka-http-marshallers-scala"),
    settings = parentSettings
  ).aggregate(httpSprayJson, httpXml)

  lazy val httpXml =
    httpMarshallersScalaSubproject("xml")

  lazy val httpSprayJson =
    httpMarshallersScalaSubproject("spray-json")
      .settings(Dependencies.httpSprayJson)
      .settings(OSGi.httpSprayJson: _*)

  lazy val httpMarshallersJava = Project(
    id = "akka-http-marshallers-java-experimental",
    base = file("akka-http-marshallers-java"),
    settings = defaultSettings ++ parentSettings
  ).aggregate(httpJackson)

  lazy val httpJackson =
    httpMarshallersJavaSubproject("jackson")

  def httpMarshallersScalaSubproject(name: String) =
    Project(
      id = s"akka-http-$name-experimental",
      base = file(s"akka-http-marshallers-scala/akka-http-$name"),
      dependencies = Seq(http),
      settings = defaultSettings
    )

  def httpMarshallersJavaSubproject(name: String) =
    Project(
      id = s"akka-http-$name-experimental",
      base = file(s"akka-http-marshallers-java/akka-http-$name"),
      dependencies = Seq(http),
      settings = defaultSettings
    )

  lazy val parsing = Project(
    id = "akka-parsing-experimental",
    base = file("akka-parsing"),
    settings = defaultSettings ++ Seq(
      scalacOptions += "-language:_",
      // ScalaDoc doesn't like the macros
      sources in doc in Compile := List()
    )
  )

  lazy val stream = Project(
    id = "akka-stream-experimental",
    base = file("akka-stream"),
    dependencies = Seq(actor),
    settings = defaultSettings ++ experimentalSettings
  )

  lazy val streamTests = Project(
    id = "akka-stream-tests-experimental",
    base = file("akka-stream-tests"),
    dependencies = Seq(streamTestkit % "test->test", stream),
    settings = defaultSettings ++ experimentalSettings
  )

  lazy val streamTestsTck = Project(
    id = "akka-stream-tck-experimental",
    base = file("akka-stream-tck"),
    dependencies = Seq(streamTestkit % "test->test", stream),
    settings = defaultSettings ++ experimentalSettings
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
      persistence % "compile;provided->provided;test->test", persistenceTck, persistenceQuery,
      typed % "compile;test->test", distributedData)
  )

  lazy val docsDev = Project(
    id = "akka-docs-dev",
    base = file("akka-docs-dev"),
    dependencies = Seq(streamTestkit % "test->test", stream, httpCore, http, httpTestkit, httpSprayJson, httpXml),
    settings = defaultSettings
  )

  lazy val contrib = Project(
    id = "akka-contrib",
    base = file("akka-contrib"),
    dependencies = Seq(remote, remoteTests % "test->test", cluster, clusterTools, persistence % "compile;test->provided")
  ) configs (MultiJvm)

  lazy val samples = Project(
    id = "akka-samples",
    base = file("akka-samples"),
    settings = parentSettings ++ ActivatorDist.settings,
    // FIXME osgiDiningHakkersSampleMavenTest temporarily removed from aggregate due to #16703
    aggregate = if (!Sample.CliOptions.aggregateSamples) Nil else
      Seq(sampleCamelJava, sampleCamelScala, sampleClusterJava, sampleClusterScala, sampleFsmScala, sampleFsmJavaLambda,
        sampleMainJava, sampleMainScala, sampleMainJavaLambda, sampleMultiNodeScala,
        samplePersistenceJava, samplePersistenceScala, samplePersistenceJavaLambda,
        sampleRemoteJava, sampleRemoteScala, sampleSupervisionJavaLambda,
        sampleDistributedDataScala, sampleDistributedDataJava)
  )

  lazy val sampleCamelJava = Sample.project("akka-sample-camel-java")
  lazy val sampleCamelScala = Sample.project("akka-sample-camel-scala")

  lazy val sampleClusterJava = Sample.project("akka-sample-cluster-java")
  lazy val sampleClusterScala = Sample.project("akka-sample-cluster-scala")

  lazy val sampleFsmScala = Sample.project("akka-sample-fsm-scala")
  lazy val sampleFsmJavaLambda = Sample.project("akka-sample-fsm-java-lambda")

  lazy val sampleMainJava = Sample.project("akka-sample-main-java")
  lazy val sampleMainScala = Sample.project("akka-sample-main-scala")
  lazy val sampleMainJavaLambda = Sample.project("akka-sample-main-java-lambda")

  lazy val sampleMultiNodeScala = Sample.project("akka-sample-multi-node-scala")

  lazy val samplePersistenceJava = Sample.project("akka-sample-persistence-java")
  lazy val samplePersistenceScala = Sample.project("akka-sample-persistence-scala")
  lazy val samplePersistenceJavaLambda = Sample.project("akka-sample-persistence-java-lambda")

  lazy val sampleRemoteJava = Sample.project("akka-sample-remote-java")
  lazy val sampleRemoteScala = Sample.project("akka-sample-remote-scala")

  lazy val sampleSupervisionJavaLambda = Sample.project("akka-sample-supervision-java-lambda")

  lazy val sampleDistributedDataScala = Sample.project("akka-sample-distributed-data-scala")
  lazy val sampleDistributedDataJava = Sample.project("akka-sample-distributed-data-java")

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
    ) ++ dontPublishSettings
  )

  val dontPublishSettings = Seq(
    publishSigned := (),
    publish := ()
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
  ) ++ dontPublishSettings

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

  private def allWarnings: Boolean = System.getProperty("akka.allwarnings", "false").toBoolean

  lazy val defaultSettings = resolverSettings ++
    TestExtras.Filter.settings ++
    Protobuf.settings ++ Seq(
    // compile options
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    scalacOptions in Compile ++= (if (allWarnings) Seq("-deprecation") else Nil),
    scalacOptions in Test := (scalacOptions in Test).value.filterNot(opt =>
      opt == "-Xlog-reflective-calls" || opt.contains("genjavadoc")),
    // -XDignore.symbol.file suppresses sun.misc.Unsafe warnings
    javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-XDignore.symbol.file"),
    javacOptions in compile ++= (if (allWarnings) Seq("-Xlint:deprecation") else Nil),
    javacOptions in doc ++= Seq(),
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

    parallelExecution in Test := System.getProperty("akka.parallelExecution", parallelExecutionByDefault.toString).toBoolean,
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
    JUnitFileReporting.settings ++
    docLintingSettings

  lazy val docLintingSettings = Seq(
     javacOptions in compile ++= Seq("-Xdoclint:none"),
     javacOptions in test ++= Seq("-Xdoclint:none"),
     javacOptions in doc ++= Seq("-Xdoclint:none")
   )

  def akkaPreviousArtifacts(id: String): Def.Initialize[Set[sbt.ModuleID]] = Def.setting {
    if (enableMiMa) {
      val versions = {
        val akka23Versions = Seq("2.3.11", "2.3.12", "2.3.13", "2.3.14")
        val akka24Versions = Seq("2.4.0")
        val akka24NewArtifacts = Seq(
          "akka-cluster-sharding",
          "akka-cluster-tools",
          "akka-persistence",
          "akka-distributed-data-experimental",
          "akka-persistence-query-experimental"
        )
        scalaBinaryVersion.value match {
          case "2.11" if !akka24NewArtifacts.contains(id) => akka23Versions ++ akka24Versions
          case _ => akka24Versions // Only Akka 2.4.x for scala > than 2.11
        }
      }

      // check against all binary compatible artifacts
      versions.map(organization.value %% id % _).toSet
    }
    else Set.empty
  }

  def akkaStreamAndHttpPreviousArtifacts(id: String): Def.Initialize[Set[sbt.ModuleID]] = Def.setting {
    // TODO fix MiMa for 2.4 Akka streams
    Set.empty
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
