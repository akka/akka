import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.httpCore
Dependencies.httpCore
MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http-core").value
