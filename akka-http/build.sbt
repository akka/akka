import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.http
Dependencies.http
MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http").value
enablePlugins(spray.boilerplate.BoilerplatePlugin)
scalacOptions in Compile += "-language:_"
