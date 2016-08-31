import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.cluster
Dependencies.cluster

// disable parallel tests
parallelExecution in Test := false

// pass on the artery enable/disable flag for the tests
jvmOptions in MultiJvm ++=
  (if (sys.props.get("akka.cluster.test.use-artery").exists(_ == "true")) Seq("-Dakka.cluster.test.use-artery=true")
  else Seq.empty)

enablePlugins(MultiNodeScalaTest)
