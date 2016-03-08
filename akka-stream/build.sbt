import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.stream
Dependencies.stream
MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-stream").value
enablePlugins(spray.boilerplate.BoilerplatePlugin)