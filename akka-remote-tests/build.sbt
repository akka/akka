import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, Unidoc }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

MultiNode.multiJvmSettings

Dependencies.remoteTests

// disable parallel tests
parallelExecution in Test := false

extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
  (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
}

scalatestOptions in MultiJvm := MultiNode.defaultMultiJvmScalatestOptions.value

publishArtifact in Compile := false

MimaKeys.reportBinaryIssues := () // disable bin comp check
