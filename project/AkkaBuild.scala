/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.FileReader
import java.io.{FileInputStream, InputStreamReader}
import java.util.Properties
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import java.time.ZoneOffset
import com.lightbend.paradox.projectinfo.ParadoxProjectInfoPluginKeys._

import sbt.Keys._
import sbt._
import JdkOptions.autoImport._
import scala.collection.breakOut

object AkkaBuild {

  val enableMiMa = true

  val parallelExecutionByDefault = false // TODO: enable this once we're sure it does not break things

  lazy val buildSettings = Def.settings(
    organization := "com.typesafe.akka",
    Dependencies.Versions,
    // use the same value as in the build scope
    version := (version in ThisBuild).value)

  lazy val currentDateTime = {
    // storing the first accessed timestamp in system property so that it will be the
    // same when build is reloaded or when using `+`.
    // `+` actually doesn't re-initialize this part of the build but that may change in the future.
    sys.props.getOrElseUpdate("akka.build.timestamp",
      DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .format(ZonedDateTime.now(ZoneOffset.UTC)))
  }

  def akkaVersion: String = {
    val default = "2.6-SNAPSHOT"
    sys.props.getOrElse("akka.build.version", default) match {
      case "timestamp" => s"2.6-$currentDateTime" // used when publishing timestamped snapshots
      case "file" => akkaVersionFromFile(default)  
      case v => v
    }
  }

  def akkaVersionFromFile(default: String): String = {
    val versionFile = "akka-actor/target/classes/version.conf"
    if (new File(versionFile).exists()) {
      val versionProps = new Properties()
      val reader = new FileReader(versionFile)
      try versionProps.load(reader) finally reader.close()
      versionProps.getProperty("akka.version", default).replaceAll("\"", "")
    } else
      default
  }

  lazy val rootSettings = Def.settings(
    Release.settings,
    UnidocRoot.akkaSettings,
    Protobuf.settings,
    parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", parallelExecutionByDefault.toString).toBoolean,
    version in ThisBuild := akkaVersion,
    // used for linking to API docs (overwrites `project-info.version`)
    ThisBuild / projectInfoVersion := { if (isSnapshot.value) "snapshot" else version.value }
  )

  lazy val mayChangeSettings = Seq(
    description := """|This module of Akka is marked as
                      |'may change', which means that it is in early
                      |access mode, which also means that it is not covered
                      |by commercial support. An module marked 'may change' doesn't
                      |have to obey the rule of staying binary compatible
                      |between minor releases. Breaking API changes may be
                      |introduced in minor releases without notice as we
                      |refine and simplify based on your feedback. Additionally
                      |such a module may be dropped in major releases
                      |without prior deprecation.
                      |""".stripMargin)

  val (mavenLocalResolver, mavenLocalResolverSettings) =
    System.getProperty("akka.build.M2Dir") match {
      case null => (Resolver.mavenLocal, Seq.empty)
      case path =>
        // Maven resolver settings
        def deliverPattern(outputPath: File): String =
          (outputPath / "[artifact]-[revision](-[classifier]).[ext]").absolutePath

        val resolver = Resolver.file("user-publish-m2-local", new File(path))
        (resolver, Seq(
          otherResolvers := resolver :: publishTo.value.toList,
          publishM2Configuration := Classpaths.publishConfig(
            publishMavenStyle.value,
            deliverPattern(crossTarget.value),
            if (isSnapshot.value) "integration" else "release",
            ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
            artifacts = packagedArtifacts.value.toVector,
            resolverName = resolver.name,
            checksums = checksums.in(publishM2).value.toVector,
            logging = ivyLoggingLevel.value,
            overwrite = true)))
    }

  lazy val resolverSettings = Def.settings(
    // should we be allowed to use artifacts published to the local maven repository
    if (System.getProperty("akka.build.useLocalMavenResolver", "false").toBoolean)
      resolvers += mavenLocalResolver
    else Seq.empty,
    // should we be allowed to use artifacts from sonatype snapshots
    if (System.getProperty("akka.build.useSnapshotSonatypeResolver", "false").toBoolean)
      resolvers += Resolver.sonatypeRepo("snapshots")
    else Seq.empty,
    pomIncludeRepository := (_ => false), // do not leak internal repositories during staging
  )

  private def allWarnings: Boolean = System.getProperty("akka.allwarnings", "false").toBoolean

  final val DefaultScalacOptions = Seq("-encoding", "UTF-8", "-feature", "-unchecked", "-Xlog-reflective-calls")

  // -XDignore.symbol.file suppresses sun.misc.Unsafe warnings
  final val DefaultJavacOptions = Seq("-encoding", "UTF-8", "-Xlint:unchecked", "-XDignore.symbol.file")

  lazy val defaultSettings: Seq[Setting[_]] = Def.settings(
    resolverSettings,
    TestExtras.Filter.settings,
    Protobuf.settings,

    // compile options
    scalacOptions in Compile ++= DefaultScalacOptions,
    scalacOptions in Compile ++=
      JdkOptions.targetJdkScalacOptions(targetSystemJdk.value, fullJavaHomes.value),
    scalacOptions in Compile ++= (if (allWarnings) Seq("-deprecation") else Nil),
    scalacOptions in Test := (scalacOptions in Test).value.filterNot(opt =>
      opt == "-Xlog-reflective-calls" || opt.contains("genjavadoc")),
    javacOptions in compile ++= DefaultJavacOptions ++
      JdkOptions.targetJdkJavacOptions(targetSystemJdk.value, fullJavaHomes.value),
    javacOptions in test ++= DefaultJavacOptions ++
      JdkOptions.targetJdkJavacOptions(targetSystemJdk.value, fullJavaHomes.value),
    javacOptions in compile ++= (if (allWarnings) Seq("-Xlint:deprecation") else Nil),
    javacOptions in doc ++= Seq(),

    crossVersion := CrossVersion.binary,

    // Adds a `src/main/scala-2.13+` source directory for Scala 2.13 and newer
    // and a `src/main/scala-2.13-` source directory for Scala version older than 2.13
    unmanagedSourceDirectories in Compile += {
      val sourceDir = (sourceDirectory in Compile).value
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n >= 13 => sourceDir / "scala-2.13+"
        case _                       => sourceDir / "scala-2.13-"
      }
    },

    ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet,

    licenses := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))),
    homepage := Some(url("https://akka.io/")),
    description := "Akka is a toolkit for building highly concurrent, distributed, and resilient message-driven applications for Java and Scala.",
    scmInfo := Some(ScmInfo(
      url("https://github.com/akka/akka"),
      "scm:git:https://github.com/akka/akka.git",
      "scm:git:git@github.com:akka/akka.git",
    )),
    apiURL := Some(url(s"https://doc.akka.io/api/akka/${version.value}")),

    initialCommands :=
      """|import language.postfixOps
         |import akka.actor._
         |import scala.concurrent._
         |import com.typesafe.config.ConfigFactory
         |import scala.concurrent.duration._
         |import akka.util.Timeout
         |var config = ConfigFactory.parseString("akka.stdout-loglevel=INFO,akka.loglevel=DEBUG,pinned{type=PinnedDispatcher,executor=thread-pool-executor,throughput=1000}")
         |var remoteConfig = ConfigFactory.parseString("akka.remote.classic.netty{port=0,use-dispatcher-for-io=akka.actor.default-dispatcher,execution-pool-size=0},akka.actor.provider=remote").withFallback(config)
         |var system: ActorSystem = null
         |implicit def _system = system
         |def startSystem(remoting: Boolean = false) { system = ActorSystem("repl", if(remoting) remoteConfig else config); println("don’t forget to system.terminate()!") }
         |implicit def ec = system.dispatcher
         |implicit val timeout = Timeout(5 seconds)
         |""".stripMargin,

    /**
     * Test settings
     */
    fork in Test := true,

    // default JVM config for tests
    javaOptions in Test ++= {
      val defaults = Seq(
        // ## core memory settings
        "-XX:+UseG1GC",
        // most tests actually don't really use _that_ much memory (>1g usually)
        // twice used (and then some) keeps G1GC happy - very few or to no full gcs
        "-Xms3g", "-Xmx3g",
        // increase stack size (todo why?)
        "-Xss2m",

        // ## extra memory/gc tuning
        // this breaks jstat, but could avoid costly syncs to disc see http://www.evanjones.ca/jvm-mmap-pause.html
        "-XX:+PerfDisableSharedMem",
        // tell G1GC that we would be really happy if all GC pauses could be kept below this as higher would
        // likely start causing test failures in timing tests
        "-XX:MaxGCPauseMillis=300",
        // nio direct memory limit for artery/aeron (probably)
        "-XX:MaxDirectMemorySize=256m",

        // faster random source
        "-Djava.security.egd=file:/dev/./urandom")

      if (sys.props.contains("akka.ci-server"))
        defaults ++ Seq("-XX:+PrintGCTimeStamps", "-XX:+PrintGCDetails")
      else
        defaults
    },

    // all system properties passed to sbt prefixed with "akka." will be passed on to the forked jvms as is
    javaOptions in Test := {
      val base = (javaOptions in Test).value
      val akkaSysProps: Seq[String] =
        sys.props.filter(_._1.startsWith("akka"))
          .map { case (key, value) => s"-D$key=$value" }(breakOut)

      base ++ akkaSysProps
    },

    // with forked tests the working directory is set to each module's home directory
    // rather than the Akka root, some tests depend on Akka root being working dir, so reset
    testGrouping in Test := {
      val original: Seq[Tests.Group] = (testGrouping in Test).value

      original.map { group =>
        group.runPolicy match {
          case Tests.SubProcess(forkOptions) =>
            group.copy(runPolicy = Tests.SubProcess(forkOptions.withWorkingDirectory(
              workingDirectory = Some(new File(System.getProperty("user.dir"))))))
          case _ => group
        }
      }
    },

    parallelExecution in Test := System.getProperty("akka.parallelExecution", parallelExecutionByDefault.toString).toBoolean,
    logBuffered in Test := System.getProperty("akka.logBufferedTests", "false").toBoolean,

    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),

    mavenLocalResolverSettings,
    docLintingSettings,
    JdkOptions.targetJdkSettings,
  )

  lazy val docLintingSettings = Seq(
    javacOptions in compile ++= Seq("-Xdoclint:none"),
    javacOptions in test ++= Seq("-Xdoclint:none"),
    javacOptions in doc ++= {
      if (JdkOptions.isJdk8) Seq("-Xdoclint:none")
      else Seq("-Xdoclint:none", "--ignore-source-errors")
    }
  )


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

  def majorMinor(version: String): Option[String] = """\d+\.\d+""".r.findFirstIn(version)
}
