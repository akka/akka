import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.streamTestkit
Dependencies.streamTestkit
MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-stream-testkit").value
