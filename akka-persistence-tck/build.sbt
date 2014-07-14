import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys
import akka.MultiNode

AkkaBuild.defaultSettings

AkkaBuild.experimentalSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

// OSGi.persistenceTck TODO: we do need to export this as OSGi bundle too?

libraryDependencies ++= Dependencies.persistenceTck

MimaKeys.previousArtifact := None

fork in Test := true

javaOptions in Test := MultiNode.defaultMultiJvmOptions
