package akka

import sbt._
import sbt.Keys._
import scala.language.implicitConversions

object Dependencies {
  import DependencyHelpers._

  val akkaVersion = "2.4.16"
  val junitVersion = "4.12"
  val h2specVersion = "1.5.0"
  val h2specName = s"h2spec_${DependencyHelpers.osName}_amd64"
  val h2specExe = "h2spec" + DependencyHelpers.exeIfWindows
  val h2specUrl = s"https://github.com/summerwind/h2spec/releases/download/v${h2specVersion}/${h2specName}.zip"

  lazy val scalaTestVersion = settingKey[String]("The version of ScalaTest to use.")
  lazy val scalaStmVersion = settingKey[String]("The version of ScalaSTM to use.")
  lazy val scalaCheckVersion = settingKey[String]("The version of ScalaCheck to use.")
  lazy val java8CompatVersion = settingKey[String]("The version of scala-java8-compat to use.")

  val Versions = Seq(
    crossScalaVersions := Seq("2.11.8", "2.12.1"),
    scalaVersion := crossScalaVersions.value.head,
    scalaCheckVersion := sys.props.get("akka.build.scalaCheckVersion").getOrElse("1.13.4"),
    scalaTestVersion := "3.0.0",
    java8CompatVersion := "0.8.0"
  )
  import Versions._


  object Compile {
    // Compile

    // when updating config version, update links ActorSystem ScalaDoc to link to the updated version
    val netty         = "io.netty"                    % "netty"                        % "3.10.6.Final" // ApacheV2

    val scalaXml      = "org.scala-lang.modules"      %% "scala-xml"                   % "1.0.5" // Scala License
    val scalaReflect  = ScalaVersionDependentModuleID.versioned("org.scala-lang" % "scala-reflect" % _) // Scala License

    // ssl-config
    val sslConfigAkka = "com.typesafe"               %% "ssl-config-akka"              % "0.2.1"       // ApacheV2

    // For akka-http spray-json support
    val sprayJson   = "io.spray"                     %% "spray-json"                   % "1.3.3"       // ApacheV2

    // For akka-http-jackson support
    val jackson     = "com.fasterxml.jackson.core"    % "jackson-databind"             % "2.7.6"       // ApacheV2

    // For akka-http-testkit-java
    val junit       = "junit"                         % "junit"                        % junitVersion  // Common Public License 1.0

    // For Java 8 Conversions
    val java8Compat = Def.setting {"org.scala-lang.modules" %% "scala-java8-compat" % java8CompatVersion.value} // Scala License

    val aeronDriver = "io.aeron"                      % "aeron-driver"                 % "1.0.1"       // ApacheV2
    val aeronClient = "io.aeron"                      % "aeron-client"                 % "1.0.1"       // ApacheV2

    val hpack       = "com.twitter"                   % "hpack"                        % "1.0.2"       // ApacheV2

    val alpnApi     = "org.eclipse.jetty.alpn"        % "alpn-api"                     % "1.1.3.v20160715" // ApacheV2

    object Docs {
      val sprayJson   = Compile.sprayJson                                                                    % "test"
      val gson        = "com.google.code.gson"        % "gson"                         % "2.3.1"             % "test"
    }

    object Test {
      val junit        = "junit"                       % "junit"                        % junitVersion       % "test" // Common Public License 1.0
      val logback      = "ch.qos.logback"              % "logback-classic"              % "1.1.3"            % "test" // EPL 1.0 / LGPL 2.1
      val mockito      = "org.mockito"                 % "mockito-all"                  % "1.10.19"          % "test" // MIT
      val scalatest    = Def.setting { "org.scalatest"  %% "scalatest"  % scalaTestVersion.value   % "test" } // ApacheV2
      val scalacheck   = Def.setting { "org.scalacheck" %% "scalacheck" % scalaCheckVersion.value  % "test" } // New BSD
      val pojosr       = "com.googlecode.pojosr"       % "de.kalpatec.pojosr.framework" % "0.2.1"            % "test" // ApacheV2
      val tinybundles  = "org.ops4j.pax.tinybundles"   % "tinybundles"                  % "1.0.0"            % "test" // ApacheV2
      val log4j        = "log4j"                       % "log4j"                        % "1.2.14"           % "test" // ApacheV2
      val junitIntf    = "com.novocode"                % "junit-interface"              % "0.11"             % "test" // MIT
      val scalaXml     = "org.scala-lang.modules"     %% "scala-xml"                    % "1.0.4"            % "test"
      val sprayJson    = Compile.sprayJson                                                                   % "test" // ApacheV2

      // in-memory filesystem for file related tests
      val jimfs        = "com.google.jimfs"            % "jimfs"                        % "1.1"              % "test" // ApacheV2

      // metrics, measurements, perf testing
      val metrics      = "com.codahale.metrics"        % "metrics-core"                 % "3.0.2"            % "test" // ApacheV2
      val metricsJvm   = "com.codahale.metrics"        % "metrics-jvm"                  % "3.0.2"            % "test" // ApacheV2
      val latencyUtils = "org.latencyutils"            % "LatencyUtils"                 % "1.0.3"            % "test" // Free BSD
      val hdrHistogram = "org.hdrhistogram"            % "HdrHistogram"                 % "2.1.9"            % "test" // CC0
      val metricsAll   = Seq(metrics, metricsJvm, latencyUtils, hdrHistogram)

      // HTTP/2
      val alpnAgent    = "org.mortbay.jetty.alpn"      % "jetty-alpn-agent"             % "2.0.5"            % "test" // ApacheV2
      val h2spec       = "io.github.summerwind"        % h2specName                     % h2specVersion      % "test" from(h2specUrl) // MIT

      // sigar logging
      val slf4jJul     = "org.slf4j"                   % "jul-to-slf4j"                 % "1.7.16"           % "test" // MIT
      val slf4jLog4j   = "org.slf4j"                   % "log4j-over-slf4j"             % "1.7.16"           % "test" // MIT

      // reactive streams tck
      val reactiveStreamsTck = "org.reactivestreams"   % "reactive-streams-tck"         % "1.0.0"            % "test" // CC0
    }

    object Provided {
      // TODO remove from "test" config
      val sigarLoader  = "io.kamon"         % "sigar-loader"        % "1.6.6-rev002"     %     "optional;provided;test" // ApacheV2

      val levelDB       = "org.iq80.leveldb"            % "leveldb"          % "0.7"    %  "optional;provided"     // ApacheV2
      val levelDBNative = "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"    %  "optional;provided"     // New BSD
    }

  }

  import Compile._

  lazy val l = libraryDependencies

  lazy val parsing = Seq(
    DependencyHelpers.versionDependentDeps(
      Dependencies.Compile.scalaReflect % "provided"
    ),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.fullMapped(nominalScalaVersion))
  )

  lazy val httpCore = l ++= Seq(
    Test.sprayJson, // for WS Autobahn test metadata
    Test.scalatest.value, Test.scalacheck.value, Test.junit)

  lazy val http = l ++= Seq()

  lazy val http2 = l ++= Seq(hpack, alpnApi)

  lazy val http2Support = l ++= Seq(Test.h2spec)

  lazy val httpTestkit = l ++= Seq(
    Test.junit, Test.junitIntf, Compile.junit % "provided",
    Test.scalatest.value.copy(configurations = Some("provided; test"))
  )

  lazy val httpTests = l ++= Seq(Test.junit, Test.scalatest.value, Test.junitIntf)

  lazy val httpXml = versionDependentDeps(scalaXml)

  lazy val httpSprayJson = Seq(
    versionDependentDeps(sprayJson),
    libraryDependencies += Test.scalatest.value
  )

  lazy val httpJackson = l ++= Seq(jackson)

  lazy val docs = l ++= Seq(Docs.sprayJson, Docs.gson)
}


object DependencyHelpers {
  case class ScalaVersionDependentModuleID(modules: String => Seq[ModuleID]) {
    def %(config: String): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => modules(version).map(_ % config))
  }
  object ScalaVersionDependentModuleID {
    implicit def liftConstantModule(mod: ModuleID): ScalaVersionDependentModuleID = versioned(_ => mod)

    def versioned(f: String => ModuleID): ScalaVersionDependentModuleID = ScalaVersionDependentModuleID(v => Seq(f(v)))
    def fromPF(f: PartialFunction[String, ModuleID]): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => if (f.isDefinedAt(version)) Seq(f(version)) else Nil)
  }

  /**
   * Use this as a dependency setting if the dependencies contain both static and Scala-version
   * dependent entries.
   */
  def versionDependentDeps(modules: ScalaVersionDependentModuleID*): Def.Setting[Seq[ModuleID]] =
    libraryDependencies ++= scalaVersion(version => modules.flatMap(m => m.modules(version))).value

  val ScalaVersion = """\d\.\d+\.\d+(?:-(?:M|RC)\d+)?""".r
  val nominalScalaVersion: String => String = {
    // matches:
    // 2.12.0-M1
    // 2.12.0-RC1
    // 2.12.0
    case version @ ScalaVersion() => version
    // transforms 2.12.0-custom-version to 2.12.0
    case version => version.takeWhile(_ != '-')
  }

  // OS name for Go binaries
  def osName = {
    val os = System.getProperty("os.name").toLowerCase()
    if (os startsWith "mac") "darwin"
    else if (os startsWith "win") "windows"
    else "linux"
  }

  def exeIfWindows = {
    val os = System.getProperty("os.name").toLowerCase()
    if (os startsWith "win") ".exe"
    else ""
  }
}
