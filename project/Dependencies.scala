package akka

import sbt._

object Dependencies {

  import DependencyHelpers._
  import DependencyHelpers.ScalaVersionDependentModuleID._

  object Versions {
    val crossScala = Seq("2.11.5", "2.10.4")
    val scalaVersion = crossScala.head
    val scalaStmVersion  = sys.props.get("akka.build.scalaStmVersion").getOrElse("0.7")
    val scalaTestVersion = sys.props.get("akka.build.scalaTestVersion").getOrElse("2.1.3")
    val scalaCheckVersion = sys.props.get("akka.build.scalaCheckVersion").getOrElse("1.11.3")
  }

  object Compile {
    import Versions._

    // Compile
    val camelCore     = "org.apache.camel"            % "camel-core"                   % "2.13.0" exclude("org.slf4j", "slf4j-api") // ApacheV2

    // when updating config version, update links ActorSystem ScalaDoc to link to the updated version
    val config        = "com.typesafe"                % "config"                       % "1.2.1"       // ApacheV2
    val netty         = "io.netty"                    % "netty"                        % "3.8.0.Final" // ApacheV2
    val protobuf      = "com.google.protobuf"         % "protobuf-java"                % "2.5.0"       // New BSD
    val scalaStm      = "org.scala-stm"              %% "scala-stm"                    % scalaStmVersion // Modified BSD (Scala)

    val slf4jApi      = "org.slf4j"                   % "slf4j-api"                    % "1.7.7"       // MIT
    // mirrored in OSGi sample
    val uncommonsMath = "org.uncommons.maths"         % "uncommons-maths"              % "1.2.2a" exclude("jfree", "jcommon") exclude("jfree", "jfreechart")      // ApacheV2
    val osgiCore      = "org.osgi"                    % "org.osgi.core"                % "4.3.1"       // ApacheV2
    val osgiCompendium= "org.osgi"                    % "org.osgi.compendium"          % "4.3.1"       // ApacheV2

    // TODO remove with metrics from akka-cluster
    val sigar         = "org.fusesource"              % "sigar"                        % "1.6.4"       // ApacheV2

    object Test {
      val commonsMath  = "org.apache.commons"          % "commons-math"                 % "2.1"              % "test" // ApacheV2
      val commonsIo    = "commons-io"                  % "commons-io"                   % "2.4"              % "test" // ApacheV2
      val commonsCodec = "commons-codec"               % "commons-codec"                % "1.7"              % "test" // ApacheV2
      val junit        = "junit"                       % "junit"                        % "4.10"             % "test" // Common Public License 1.0
      val logback      = "ch.qos.logback"              % "logback-classic"              % "1.0.13"           % "test" // EPL 1.0 / LGPL 2.1
      val mockito      = "org.mockito"                 % "mockito-all"                  % "1.9.5"            % "test" // MIT
      // changing the scalatest dependency must be reflected in akka-docs/rst/dev/multi-jvm-testing.rst
      val scalatest    = "org.scalatest"              %% "scalatest"                    % scalaTestVersion   % "test" // ApacheV2
      val scalacheck   = "org.scalacheck"             %% "scalacheck"                   % scalaCheckVersion  % "test" // New BSD
      val pojosr       = "com.googlecode.pojosr"       % "de.kalpatec.pojosr.framework" % "0.2.1"            % "test" // ApacheV2
      val tinybundles  = "org.ops4j.pax.tinybundles"   % "tinybundles"                  % "1.0.0"            % "test" // ApacheV2
      val log4j        = "log4j"                       % "log4j"                        % "1.2.14"           % "test" // ApacheV2
      val junitIntf    = "com.novocode"                % "junit-interface"              % "0.8"              % "test" // MIT
      val scalaXml     = post210Dependency("org.scala-lang.modules" %% "scala-xml" % "1.0.1"  % "test")

      // metrics, measurements, perf testing
      val metrics         = "com.codahale.metrics"        % "metrics-core"                 % "3.0.1"            % "test" // ApacheV2
      val metricsJvm      = "com.codahale.metrics"        % "metrics-jvm"                  % "3.0.1"            % "test" // ApacheV2
      val latencyUtils    = "org.latencyutils"            % "LatencyUtils"                 % "1.0.3"            % "test" // Free BSD
      val hdrHistogram    = "org.hdrhistogram"            % "HdrHistogram"                 % "1.1.4"            % "test" // CC0
      val metricsAll      = Seq(metrics, metricsJvm, latencyUtils, hdrHistogram)

      // sigar logging
      val slf4jJul      = "org.slf4j"                   % "jul-to-slf4j"                 % "1.7.7"    % "test"    // MIT
      val slf4jLog4j    = "org.slf4j"                   % "log4j-over-slf4j"             % "1.7.7"    % "test"    // MIT
    }

    object Provided {
      // TODO remove from "test" config
      val sigarLoader  = "io.kamon"         % "sigar-loader"        % "1.6.5-rev001"     %     "optional;provided;test" // ApacheV2
      
      val levelDB       = "org.iq80.leveldb"            % "leveldb"          % "0.7"    %  "optional;provided"     // ApacheV2
      val levelDBNative = "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.7"    %  "optional;provided"     // New BSD
    }
    
  }

  import Compile._

  val actor = Seq(config)

  val testkit = Seq(Test.junit, Test.scalatest) ++ Test.metricsAll

  val actorTests = Seq(Test.junit, Test.scalatest, Test.commonsCodec, Test.commonsMath, Test.mockito, Test.scalacheck, protobuf, Test.junitIntf)

  val remote = Seq(netty, protobuf, uncommonsMath, Test.junit, Test.scalatest)

  val remoteTests = deps(Test.junit, Test.scalatest, Test.scalaXml)

  val cluster = Seq(Test.junit, Test.scalatest)

  val clusterMetrics = Seq(Provided.sigarLoader, Test.slf4jJul, Test.slf4jLog4j, Test.logback, Test.mockito)

  val slf4j = Seq(slf4jApi, Test.logback)

  val agent = Seq(scalaStm, Test.scalatest, Test.junit)

  val persistence = deps(protobuf, Provided.levelDB, Provided.levelDBNative, Test.scalatest, Test.junit, Test.commonsIo, Test.scalaXml)

  val persistenceTck = Seq(Test.scalatest.copy(configurations = Some("compile")), Test.junit.copy(configurations = Some("compile")))

  val kernel = Seq(Test.scalatest, Test.junit)

  val camel = Seq(camelCore, Test.scalatest, Test.junit, Test.mockito, Test.logback, Test.commonsIo, Test.junitIntf)

  val osgi = Seq(osgiCore, osgiCompendium, Test.logback, Test.commonsIo, Test.pojosr, Test.tinybundles, Test.scalatest, Test.junit)

  val docs = Seq(Test.scalatest, Test.junit, Test.junitIntf)

  val contrib = Seq(Test.junitIntf, Test.commonsIo)
}

object DependencyHelpers {

  import sbt.Keys._

  case class ScalaVersionDependentModuleID(val modules: String => Seq[ModuleID]) {
    def %(config: String): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => modules(version).map(_ % config))
  }

  object ScalaVersionDependentModuleID {
    implicit def liftConstantModule(mod: ModuleID): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(_ => Seq(mod))

    def fromPF(f: PartialFunction[String, ModuleID]): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => if (f.isDefinedAt(version)) Seq(f(version)) else Nil)

    def post210Dependency(moduleId: ModuleID): ScalaVersionDependentModuleID = ScalaVersionDependentModuleID.fromPF {
      case version if !version.startsWith("2.10") => moduleId
    }
  }

  /**
   * Use this as a dependency setting if the dependencies contain both static and Scala-version
   * dependent entries.
   */
  def deps(modules: ScalaVersionDependentModuleID*) =
    libraryDependencies <++= scalaVersion(version => modules.flatMap(m => m.modules(version)))
}
