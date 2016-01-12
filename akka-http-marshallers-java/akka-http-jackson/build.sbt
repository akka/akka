import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.httpJackson

Dependencies.httpJackson

MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http-jackson").value

