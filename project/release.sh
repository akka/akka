#!/bin/sh
sbt -Dpublish.maven.central=true -Dakka.genjavadoc.enabled=true publishSigned
sbt ';project docs; paradox'
