package akka

import sbt._
import Keys._

object Dependencies {

  lazy val scalaTestVersion = settingKey[String]("The version of ScalaTest to use.")
  lazy val scalaStmVersion = settingKey[String]("The version of ScalaSTM to use.")
  lazy val scalaCheckVersion = settingKey[String]("The version of ScalaCheck to use.")

  val Versions = Seq(
    crossScalaVersions := Seq("2.11.7"), //"2.12.0-M2"
    scalaVersion := crossScalaVersions.value.head,
    scalaStmVersion := sys.props.get("akka.build.scalaStmVersion").getOrElse("0.7"),
    scalaCheckVersion := sys.props.get("akka.build.scalaCheckVersion").getOrElse("1.11.6"),
    scalaTestVersion := (if (scalaVersion.value == "2.12.0-M2") "2.2.5-M2" else "2.2.4")
  )

  object Compile {
    // Compile

    // FIXME: change to project dependency once akka-stream merged to master
    val akkaStream = "com.typesafe.akka" %% "akka-stream-experimental" % "1.0"

    val camelCore     = "org.apache.camel"            % "camel-core"                   % "2.13.4" exclude("org.slf4j", "slf4j-api") // ApacheV2

    // when updating config version, update links ActorSystem ScalaDoc to link to the updated version
    val config        = "com.typesafe"                % "config"                       % "1.3.0"       // ApacheV2
    val netty         = "io.netty"                    % "netty"                        % "3.10.3.Final" // ApacheV2
    val scalaStm      = Def.setting { "org.scala-stm" %% "scala-stm" % scalaStmVersion.value } // Modified BSD (Scala)

    val slf4jApi      = "org.slf4j"                   % "slf4j-api"                    % "1.7.12"       // MIT
    // mirrored in OSGi sample
    val uncommonsMath = "org.uncommons.maths"         % "uncommons-maths"              % "1.2.2a" exclude("jfree", "jcommon") exclude("jfree", "jfreechart")      // ApacheV2
    val osgiCore      = "org.osgi"                    % "org.osgi.core"                % "4.3.1"       // ApacheV2
    val osgiCompendium= "org.osgi"                    % "org.osgi.compendium"          % "4.3.1"       // ApacheV2

    // TODO remove with metrics from akka-cluster
    val sigar         = "org.fusesource"              % "sigar"                        % "1.6.4"       // ApacheV2

    object Docs {
      val sprayJson   = "io.spray"                   %%  "spray-json"                  % "1.3.2"             % "test"
      val gson        = "com.google.code.gson"        % "gson"                         % "2.3.1"             % "test"
    }

    object Test {
      val commonsMath  = "org.apache.commons"          % "commons-math"                 % "2.2"              % "test" // ApacheV2
      val commonsIo    = "commons-io"                  % "commons-io"                   % "2.4"              % "test" // ApacheV2
      val commonsCodec = "commons-codec"               % "commons-codec"                % "1.10"             % "test" // ApacheV2
      val junit        = "junit"                       % "junit"                        % "4.12"             % "test" // Common Public License 1.0
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
      // FIXME: change to project dependency once akka-stream merged to master
      val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit-experimental" % "1.0" % "test"

      // metrics, measurements, perf testing
      val metrics         = "com.codahale.metrics"        % "metrics-core"                 % "3.0.2"            % "test" // ApacheV2
      val metricsJvm      = "com.codahale.metrics"        % "metrics-jvm"                  % "3.0.2"            % "test" // ApacheV2
      val latencyUtils    = "org.latencyutils"            % "LatencyUtils"                 % "1.0.3"            % "test" // Free BSD
      val hdrHistogram    = "org.hdrhistogram"            % "HdrHistogram"                 % "1.1.4"            % "test" // CC0
      val metricsAll      = Seq(metrics, metricsJvm, latencyUtils, hdrHistogram)

      // sigar logging
      val slf4jJul      = "org.slf4j"                   % "jul-to-slf4j"                 % "1.7.12"    % "test"    // MIT
      val slf4jLog4j    = "org.slf4j"                   % "log4j-over-slf4j"             % "1.7.12"    % "test"    // MIT
    }

    object Provided {
      // TODO remove from "test" config
      val sigarLoader  = "io.kamon"         % "sigar-loader"        % "1.6.6-rev002"     %     "optional;provided;test" // ApacheV2
      
      val levelDB       = "org.iq80.leveldb"            % "leveldb"          % "0.7"    %  "optional;provided"     // ApacheV2
      val levelDBNative = "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"    %  "optional;provided"     // New BSD
    }
    
  }

  import Compile._
  val l = libraryDependencies

  val actor = l ++= Seq(config)

  val testkit = l ++= Seq(Test.junit, Test.scalatest.value) ++ Test.metricsAll

  val actorTests = l ++= Seq(Test.junit, Test.scalatest.value, Test.commonsCodec, Test.commonsMath, Test.mockito, Test.scalacheck.value, Test.junitIntf)

  val remote = l ++= Seq(netty, uncommonsMath, Test.junit, Test.scalatest.value)

  val remoteTests = l ++= Seq(Test.junit, Test.scalatest.value, Test.scalaXml)

  val cluster = l ++= Seq(Test.junit, Test.scalatest.value)
  
  val clusterTools = l ++= Seq(Test.junit, Test.scalatest.value)
  
  val clusterSharding = l ++= Seq(Provided.levelDB, Provided.levelDBNative, Test.junit, Test.scalatest.value, Test.commonsIo)

  val clusterMetrics = l ++= Seq(Provided.sigarLoader, Test.slf4jJul, Test.slf4jLog4j, Test.logback, Test.mockito)
  
  val distributedData = l ++= Seq(Test.junit, Test.scalatest.value)

  val slf4j = l ++= Seq(slf4jApi, Test.logback)

  val agent = l ++= Seq(scalaStm.value, Test.scalatest.value, Test.junit)

  val persistence = l ++= Seq(Provided.levelDB, Provided.levelDBNative, Test.scalatest.value, Test.junit, Test.commonsIo, Test.scalaXml)

  val persistenceQuery = l ++= Seq(akkaStream, Test.scalatest.value, Test.junit, Test.commonsIo, Test.akkaStreamTestkit)

  val persistenceTck = l ++= Seq(Test.scalatest.value.copy(configurations = Some("compile")), Test.junit.copy(configurations = Some("compile")))

  val kernel = l ++= Seq(Test.scalatest.value, Test.junit)

  val camel = l ++= Seq(camelCore, Test.scalatest.value, Test.junit, Test.mockito, Test.logback, Test.commonsIo, Test.junitIntf)

  val osgi = l ++= Seq(osgiCore, osgiCompendium, Test.logback, Test.commonsIo, Test.pojosr, Test.tinybundles, Test.scalatest.value, Test.junit)

  val docs = l ++= Seq(Test.scalatest.value, Test.junit, Test.junitIntf, Docs.sprayJson, Docs.gson)

  val contrib = l ++= Seq(Test.junitIntf, Test.commonsIo)
  
  val benchJmh = l ++= Seq(Provided.levelDB, Provided.levelDBNative)
}
