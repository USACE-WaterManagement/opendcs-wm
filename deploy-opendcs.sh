#!/bin/bash

echo "See if you see access failures or retries these commands require the DA-WME-DataProcessing-Access role to be active."

if [ "$1" == "" ]; then
    echo "You must supply the version to retrieve"
    exit 1
fi

VERSION=$1 # TODO, this should be used as an environment variable in the Dockerfile as well.
SET_ACTIVE=$2
AWS_PROFILE=$3
AWS_REGISTRY=$4
MARKER="a"
if [ "$5" != "" ]; then
    MARKER="$5"
fi

echo "Retrieving LRGS container image."
docker pull ghcr.io/opendcs/lrgs:$VERSION
docker tag ghcr.io/opendcs/lrgs:$VERSION $AWS_REGISTRY/wme-opendcs-lrgs:$VERSION
docker tag ghcr.io/opendcs/lrgs:$VERSION $AWS_REGISTRY/wme-opendcs-lrgs:test

echo "Building CWMS/WMES custom variant image."
docker build \
       -t $AWS_REGISTRY/wme-opendcs-dependency-updater:$VERSION \
       --target compdepends \
       --build-arg VERSION=$VERSION  \
       --build-arg MARKER=$MARKER \
       .
docker tag $AWS_REGISTRY/wme-opendcs-dependency-updater:$VERSION $AWS_REGISTRY/wme-opendcs-dependency-updater:test

docker build \
       -t $AWS_REGISTRY/wme-opendcs-computation-processor:$VERSION \
       --target compproc \
       --build-arg VERSION=$VERSION  \
       --build-arg MARKER=$MARKER \
       .
docker tag $AWS_REGISTRY/wme-opendcs-computation-processor:$VERSION $AWS_REGISTRY/wme-opendcs-computation-processor:test
docker build \
       -t $AWS_REGISTRY/wme-opendcs-routing-scheduler:$VERSION \
       --target routingscheduler \
       --build-arg VERSION=$VERSION  \
       --build-arg MARKER=$MARKER \
       .
docker tag $AWS_REGISTRY/wme-opendcs-routing-scheduler:$VERSION $AWS_REGISTRY/wme-opendcs-routing-scheduler:test

echo "Logining in to AWS ECR, $AWS_REGISTRY using AWS Profile: $AWS_PROFILE"
aws ecr get-login-password --region us-gov-west-1 --profile $AWS_PROFILE | docker login --username AWS --password-stdin $AWS_REGISTRY

echo "Pushing versioned image tag to ECR."
docker push $AWS_REGISTRY/wme-opendcs-lrgs:$VERSION
docker push $AWS_REGISTRY/wme-opendcs-dependency-updater:$VERSION
docker push $AWS_REGISTRY/wme-opendcs-computation-processor:$VERSION
docker push $AWS_REGISTRY/wme-opendcs-routing-scheduler:$VERSION

if [ "$SET_ACTIVE" == "true" ]; then
    echo "Setting active tag to current build."
    docker push $AWS_REGISTRY/wme-opendcs-lrgs:test
    docker push $AWS_REGISTRY/wme-opendcs-dependency-updater:test
    docker push $AWS_REGISTRY/wme-opendcs-computation-processor:test
    docker push $AWS_REGISTRY/wme-opendcs-routing-scheduler:test
fi