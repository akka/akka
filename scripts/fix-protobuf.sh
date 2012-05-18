#!/bin/bash

find . -name \*.java -print0 | xargs -0 perl -pi -e 's/\Qprivate Builder(BuilderParent parent)/private Builder(com.google.protobuf.GeneratedMessage.BuilderParent parent)/'
