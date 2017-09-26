import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Protobuf, MultiNodeScalaTest }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.cluster
Dependencies.cluster
Protobuf.settings

// disable parallel tests
parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
