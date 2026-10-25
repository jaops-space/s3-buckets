#!/bin/bash
set -e

cd `dirname $0`/..
home=`pwd`

if [[ -n $(git status -s) ]]; then
    read -p 'Your workspace contains dirty or untracked files. These will not be part of your release. Continue? [Y/n] ' yesNo
    if [[ -n $yesNo ]] && [[ $yesNo == 'n' ]]; then
        exit 0
    fi
fi

pomversion=`mvn -q help:evaluate -Dexpression=project.version -DforceStdout`
read -p "Enter the new version to set [$pomversion] " newVersion
if [[ -n $newVersion ]]; then
    pomversion=$newVersion
    mvn versions:set -DnewVersion=$newVersion versions:commit
fi

if [[ $pomversion == *-SNAPSHOT ]]; then
    snapshot=1
    version=${pomversion/-SNAPSHOT/}
else
    snapshot=0
    version=$pomversion
fi

if [[ -n $(git status -s) ]]; then
    git commit . -v -em"Prepare release s3-buckets-${version}" || :
    if [ $snapshot -eq 0 ]; then
        git tag s3-buckets-$version
    fi
fi

mvn -q clean

clonedir=$home/distribution/target/s3-buckets-clone

rm -rf $clonedir
mkdir -p $clonedir
git clone . $clonedir
rm -rf $clonedir/.git

cd $clonedir

mvn package -P jaops-release -DskipTests

cd $home

ls -lh $clonedir/target/*.jar $clonedir/target/*.asc
echo

if [ $snapshot -eq 0 ]; then
    read -p "Do you want to upload $pomversion maven artifacts to Maven Central? [y/N] " yesNo
    if [[ $yesNo == 'y' ]]; then
        mvn -f $clonedir -P jaops-release -DskipTests deploy
        echo 'Check and publish the deployment at https://central.sonatype.com/publishing/deployments'
    fi
else
    # Requires SNAPSHOTs to be enabled for the com.jaops namespace in the Central Portal
    read -p "Do you want to publish $pomversion maven artifacts to Maven Central Snapshots? [y/N] " yesNo
    if [[ $yesNo == 'y' ]]; then
        mvn -f $clonedir -P jaops-release -DskipTests deploy
    fi
fi

rm -rf $clonedir

# Upgrade version in pom.xml files
# For example: 1.2.3 --> 1.2.4-SNAPSHOT
if [ $snapshot -eq 0 ]; then
    if [[ $version =~ ([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
        developmentVersion=${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))-SNAPSHOT
        mvn versions:set -DnewVersion=$developmentVersion versions:commit
        git commit . -v -em"Prepare next development iteration"
    else
        echo 'Failed to set development version'
        exit 1
    fi
fi
