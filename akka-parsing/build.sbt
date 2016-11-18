import akka._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

Dependencies.parsing

unmanagedSourceDirectories in ScalariformKeys.format in Test := (unmanagedSourceDirectories in Test).value
scalacOptions += "-language:_"

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
