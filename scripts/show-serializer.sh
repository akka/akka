#!/bin/bash

# Locate akka.serialization.Serializer.identifier()
echo "## In (Scala) code"
find . -name '*.scala' -type f | xargs grep "def identifier =" | sort

echo "## In reference.conf"
awk '/serialization-identifiers/,/}/' */src/main/resources/reference.conf
