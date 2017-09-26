import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest, Protobuf, SigarLoader }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._

AkkaBuild.defaultSettings
Formatting.formatSettings
SigarLoader.sigarSettings
OSGi.clusterMetrics
Dependencies.clusterMetrics
Protobuf.settings

parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
