#!/bin/bash

VERSION=$1

if [ -z "$VERSION" ]; then
    echo "Usage: push-release.sh VERSION"
    exit 1
fi

source ~/.akka-release

if [ -z "$AKKA_RELEASE_SERVER" ]; then
    echo "Need AKKA_RELEASE_SERVER to be specified"
    exit 1
fi

if [ -z "$AKKA_RELEASE_PATH" ]; then
    echo "Need AKKA_RELEASE_PATH to be specified"
    exit 1
fi

ref=$(git symbolic-ref HEAD 2> /dev/null)
branch=${ref#refs/heads/}

git push origin $branch
git push origin --tags

release="target/release/${VERSION}"
tmp="/tmp/akka-release-${VERSION}"

rsync -avz ${release}/ ${AKKA_RELEASE_SERVER}:${tmp}/
echo "Verify sudo on $AKKA_RELEASE_SERVER"
ssh -t ${AKKA_RELEASE_SERVER} sudo -v
ssh -t ${AKKA_RELEASE_SERVER} sudo rsync -rpt ${tmp}/ ${AKKA_RELEASE_PATH}
ssh -t ${AKKA_RELEASE_SERVER} rm -rf ${tmp}
