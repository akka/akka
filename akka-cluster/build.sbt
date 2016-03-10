import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.cluster
Dependencies.cluster

// disable parallel tests
parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
