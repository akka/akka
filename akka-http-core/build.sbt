import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.httpCore

Dependencies.httpCore

MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http-core").value
