#!/bin/bash

projdir=$(cd $(dirname $0); pwd)
version=2.2.0-SNAPSHOT

# This directory is specified in the build as the root of the tar
# Use tar --strip-components=1 to ignore the root
outputdir="$projdir/target/akka-sample-osgi-dining-hakkers-$version"

if [[ -d "$outputdir" ]]; then
  echo Deleting existing $outputdir...
  rm -fr "$outputdir"
fi
echo Extracting configured container into $outputdir...
tar -C $projdir -zxf assembly-dist/target/akka-sample-osgi-dining-hakkers-dist-$version.tar.gz
echo Extract complete, please run $outputdir/bin/karaf
