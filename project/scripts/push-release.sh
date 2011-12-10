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

rsync -rlpvz --chmod=Dg+ws,Fg+w ${release}/ ${AKKA_RELEASE_SERVER}:${AKKA_RELEASE_PATH}/
