#!/bin/bash

# Locate akka.serialization.Serializer.identifier()
find . -name *.scala | xargs grep "def identifier =" * | sort
