import akka.{ AkkaBuild, Dependencies, Formatting, MultiNodeScalaTest, Unidoc }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Dependencies.remoteTests

// disable parallel tests
parallelExecution in Test := false

publishArtifact in Compile := false

enablePlugins(MultiNodeScalaTest)

AkkaBuild.dontPublishSettings