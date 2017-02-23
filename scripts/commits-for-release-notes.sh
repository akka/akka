#!/bin/sh

set -e

ROOT_DIR=$(dirname $(readlink -f $0))/..

LAST_VERSION=$1

echo "Changes in akka-http-core"
echo
git log --no-merges --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http-core

echo
echo "Changes in akka-http"
echo
git log --no-merges --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http

echo
echo "Changes in akka-http-marshallers"
echo
git log --no-merges --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http-marshallers*

echo
echo "Changes in akka-http-testkit"
echo
git log --no-merges --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http-testkit

echo
echo "Changes in docs"
echo
git log --no-merges --oneline ${LAST_VERSION}.. -- $ROOT_DIR/docs

echo
echo "Changes in akka-http2-support"
echo
git log --no-merges --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http2-support

echo
echo "Changes in build"
echo
git log --no-merges --oneline ${LAST_VERSION}.. -- $ROOT_DIR/project $ROOT_DIR/*.sbt

