/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import Keys._

object Dependencies {
  import DependencyHelpers._

  lazy val scalaTestVersion = settingKey[String]("The version of ScalaTest to use.")
  lazy val scalaStmVersion = settingKey[String]("The version of ScalaSTM to use.")
  lazy val scalaCheckVersion = settingKey[String]("The version of ScalaCheck to use.")
  lazy val java8CompatVersion = settingKey[String]("The version of scala-java8-compat to use.")
  val junitVersion = "4.12"
  val sslConfigVersion = "0.2.1"

  val Versions = Seq(
    crossScalaVersions := Seq("2.11.8"), // "2.12.0"
    scalaVersion := crossScalaVersions.value.head,
    scalaStmVersion := sys.props.get("akka.build.scalaStmVersion").getOrElse("0.8"),
    scalaCheckVersion := sys.props.get("akka.build.scalaCheckVersion").getOrElse(
      if (scalaVersion.value.startsWith("2.12")) "1.13.4" // does not work for 2.11
      else "1.13.2"
    ),
    scalaTestVersion := "3.0.0",
    java8CompatVersion := {
      scalaVersion.value match {
        case x if x.startsWith("2.12") => "0.8.0"
        case _ => "0.7.0"
      }
    }
  )

  object Compile {
    // Compile

    val camelCore     = "org.apache.camel"            % "camel-core"                   % "2.13.4" exclude("org.slf4j", "slf4j-api") // ApacheV2

    // when updating config version, update links ActorSystem ScalaDoc to link to the updated version
    val config        = "com.typesafe"                % "config"                       % "1.3.0"       // ApacheV2
    val netty         = "io.netty"                    % "netty"                        % "3.10.6.Final" // ApacheV2
    val scalaStm      = Def.setting { "org.scala-stm" %% "scala-stm" % scalaStmVersion.value } // Modified BSD (Scala)

    val scalaXml      = "org.scala-lang.modules"      %% "scala-xml"                   % "1.0.5" // Scala License
    val scalaReflect  = ScalaVersionDependentModuleID.versioned("org.scala-lang" % "scala-reflect" % _) // Scala License

    val slf4jApi      = "org.slf4j"                   % "slf4j-api"                    % "1.7.16"       // MIT

        // mirrored in OSGi sample
    val uncommonsMath = "org.uncommons.maths"         % "uncommons-maths"              % "1.2.2a" exclude("jfree", "jcommon") exclude("jfree", "jfreechart")      // ApacheV2
    val osgiCore      = "org.osgi"                    % "org.osgi.core"                % "4.3.1"       // ApacheV2
    val osgiCompendium= "org.osgi"                    % "org.osgi.compendium"          % "4.3.1"       // ApacheV2

    // TODO remove with metrics from akka-cluster
    val sigar         = "org.fusesource"              % "sigar"                        % "1.6.4"       // ApacheV2

    // reactive streams
    val reactiveStreams = "org.reactivestreams"       % "reactive-streams"             % "1.0.0" // CC0

    // ssl-config
    val sslConfigCore = "com.typesafe"                %% "ssl-config-core"             % sslConfigVersion // ApacheV2
    
    val lmdb          = "org.lmdbjava"                % "lmdbjava"                     % "0.0.4" // ApacheV2, OpenLDAP Public License
    
    // For akka-http-testkit-java
    val junit       = "junit"                         % "junit"                        % junitVersion  // Common Public License 1.0

    // For Java 8 Conversions
    val java8Compat = Def.setting {"org.scala-lang.modules" %% "scala-java8-compat" % java8CompatVersion.value} // Scala License
    
    val aeronDriver = "io.aeron"                      % "aeron-driver"                 % "1.0.4"       // ApacheV2
    val aeronClient = "io.aeron"                      % "aeron-client"                 % "1.0.4"       // ApacheV2

    object Docs {
      val sprayJson   = "io.spray"                   %%  "spray-json"                  % "1.3.2"             % "test"
      val gson        = "com.google.code.gson"        % "gson"                         % "2.3.1"             % "test"
    }

    object Test {
      val commonsMath  = "org.apache.commons"          % "commons-math"                 % "2.2"              % "test" // ApacheV2
      val commonsIo    = "commons-io"                  % "commons-io"                   % "2.4"              % "test" // ApacheV2
      val commonsCodec = "commons-codec"               % "commons-codec"                % "1.10"             % "test" // ApacheV2
      val junit        = "junit"                       % "junit"                        % junitVersion       % "test" // Common Public License 1.0
      val logback      = "ch.qos.logback"              % "logback-classic"              % "1.1.3"            % "test" // EPL 1.0 / LGPL 2.1
      val mockito      = "org.mockito"                 % "mockito-all"                  % "1.10.19"          % "test" // MIT
      // changing the scalatest dependency must be reflected in akka-docs/rst/dev/multi-jvm-testing.rst
      val scalatest    = Def.setting { "org.scalatest"  %% "scalatest"  % scalaTestVersion.value   % "test" } // ApacheV2
      val scalacheck   = Def.setting { "org.scalacheck" %% "scalacheck" % scalaCheckVersion.value  % "test" } // New BSD
      val pojosr       = "com.googlecode.pojosr"       % "de.kalpatec.pojosr.framework" % "0.2.1"            % "test" // ApacheV2
      val tinybundles  = "org.ops4j.pax.tinybundles"   % "tinybundles"                  % "1.0.0"            % "test" // ApacheV2
      val log4j        = "log4j"                       % "log4j"                        % "1.2.14"           % "test" // ApacheV2
      val junitIntf    = "com.novocode"                % "junit-interface"              % "0.11"             % "test" // MIT
      val scalaXml     = "org.scala-lang.modules"     %% "scala-xml"                    % "1.0.4"            % "test"

      // in-memory filesystem for file related tests
      val jimfs        = "com.google.jimfs"            % "jimfs"                        % "1.1"              % "test" // ApacheV2

      // metrics, measurements, perf testing
      val metrics         = "com.codahale.metrics"        % "metrics-core"                 % "3.0.2"            % "test" // ApacheV2
      val metricsJvm      = "com.codahale.metrics"        % "metrics-jvm"                  % "3.0.2"            % "test" // ApacheV2
      val latencyUtils    = "org.latencyutils"            % "LatencyUtils"                 % "1.0.3"            % "test" // Free BSD
      val hdrHistogram    = "org.hdrhistogram"            % "HdrHistogram"                 % "2.1.9"            % "test" // CC0
      val metricsAll      = Seq(metrics, metricsJvm, latencyUtils, hdrHistogram)

      // sigar logging
      val slf4jJul      = "org.slf4j"                   % "jul-to-slf4j"                 % "1.7.16"    % "test"    // MIT
      val slf4jLog4j    = "org.slf4j"                   % "log4j-over-slf4j"             % "1.7.16"    % "test"    // MIT

      // reactive streams tck
      val reactiveStreamsTck = "org.reactivestreams" % "reactive-streams-tck" % "1.0.0" % "test" // CC0
    }

    object Provided {
      // TODO remove from "test" config
      val sigarLoader  = "io.kamon"         % "sigar-loader"        % "1.6.6-rev002"     %     "optional;provided;test" // ApacheV2

      val levelDB       = "org.iq80.leveldb"            % "leveldb"          % "0.7"    %  "optional;provided"     // ApacheV2
      val levelDBNative = "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"    %  "optional;provided"     // New BSD
    }

  }

  import Compile._
  // TODO check if `l ++=` everywhere expensive?
  val l = libraryDependencies

  val actor = l ++= Seq(config, java8Compat.value)

  val testkit = l ++= Seq(Test.junit, Test.scalatest.value) ++ Test.metricsAll

  val actorTests = l ++= Seq(Test.junit, Test.scalatest.value, Test.commonsCodec, Test.commonsMath, Test.mockito, Test.scalacheck.value, Test.junitIntf)

  val remote = l ++= Seq(netty, uncommonsMath, aeronDriver, aeronClient, Test.junit, Test.scalatest.value, Test.jimfs)

  val remoteTests = l ++= Seq(Test.junit, Test.scalatest.value, Test.scalaXml)

  val cluster = l ++= Seq(Test.junit, Test.scalatest.value)

  val clusterTools = l ++= Seq(Test.junit, Test.scalatest.value)

  val clusterSharding = l ++= Seq(Provided.levelDB, Provided.levelDBNative, Test.junit, Test.scalatest.value, Test.commonsIo)

  val clusterMetrics = l ++= Seq(Provided.sigarLoader, Test.slf4jJul, Test.slf4jLog4j, Test.logback, Test.mockito)

  val distributedData = l ++= Seq(lmdb, Test.junit, Test.scalatest.value)

  val slf4j = l ++= Seq(slf4jApi, Test.logback)

  val agent = l ++= Seq(scalaStm.value, Test.scalatest.value, Test.junit)

  val persistence = l ++= Seq(Provided.levelDB, Provided.levelDBNative, Test.scalatest.value, Test.junit, Test.commonsIo, Test.commonsCodec, Test.scalaXml)

  val persistenceQuery = l ++= Seq(Test.scalatest.value, Test.junit, Test.commonsIo)

  val persistenceTck = l ++= Seq(Test.scalatest.value.copy(configurations = Some("compile")), Test.junit.copy(configurations = Some("compile")))

  val persistenceShared = l ++= Seq(Provided.levelDB, Provided.levelDBNative)

  val kernel = l ++= Seq(Test.scalatest.value, Test.junit)

  val camel = l ++= Seq(camelCore, Test.scalatest.value, Test.junit, Test.mockito, Test.logback, Test.commonsIo, Test.junitIntf)

  val osgi = l ++= Seq(osgiCore, osgiCompendium, Test.logback, Test.commonsIo, Test.pojosr, Test.tinybundles, Test.scalatest.value, Test.junit)

  val docs = l ++= Seq(Test.scalatest.value, Test.junit, Test.junitIntf, Docs.sprayJson, Docs.gson)

  val contrib = l ++= Seq(Test.junitIntf, Test.commonsIo)

  val benchJmh = l ++= Seq(Provided.levelDB, Provided.levelDBNative)

  // akka stream

  lazy val stream = l ++= Seq[sbt.ModuleID](
    reactiveStreams,
    sslConfigCore,
    Test.junitIntf,
    Test.scalatest.value)

  lazy val streamTestkit = l ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit)

  lazy val streamTests = l ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit, Test.commonsIo, Test.jimfs)

  lazy val streamTestsTck = l ++= Seq(Test.scalatest.value, Test.scalacheck.value, Test.junit, Test.reactiveStreamsTck)

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
    libraryDependencies <++= scalaVersion(version => modules.flatMap(m => m.modules(version)))

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
}
