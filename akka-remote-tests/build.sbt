import akka.{ AkkaBuild, Dependencies, Formatting, MultiNodeScalaTest, Protobuf }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.formatSettings
Dependencies.remoteTests
Protobuf.settings

// disable parallel tests
parallelExecution in Test := false
publishArtifact in Compile := false

enablePlugins(MultiNodeScalaTest)
disablePlugins(MimaPlugin)
