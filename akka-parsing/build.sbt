import akka._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

Dependencies.parsing
OSGi.parsing

unmanagedSourceDirectories in ScalariformKeys.format in Test := (unmanagedSourceDirectories in Test).value
scalacOptions += "-language:_"

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
