#!/usr/bin/env bash

root=$(
	cd $(dirname $(readlink $0 || echo $0))/..
	pwd
)

sbtver=0.13.7
sbtjar=sbt-launch.jar
sbtsum=7341059aa30c953021d6af41c89d2cac

function download {
	echo "downloading ${sbtjar}" 1>&2
	wget -q "http://dl.bintray.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/${sbtver}/jars/${sbtjar}"
	mkdir -p target/ && mv ${sbtjar} target/${sbtjar}
}

function sbtjar_md5 {
	openssl md5 < target/${sbtjar} | cut -f2 -d'=' | awk '{print $1}'
}

if [ ! -f "target/${sbtjar}" ]; then
	download
fi

test -f "target/${sbtjar}" || exit 1

jarmd5=$(sbtjar_md5)
if [ "${jarmd5}" != "${sbtsum}" ]; then
	echo "Bad MD5 checksum on ${sbtjar}!" 1>&2
	echo "Moving current sbt-launch.jar to sbt-launch.jar.old!" 1>&2
	mv "target/${sbtjar}" "target/${sbtjar}.old"
	download

	jarmd5=$(sbtjar_md5)
	if [ "${jarmd5}" != "${sbtsum}" ]; then
		echo "Bad MD5 checksum *AGAIN*!" 1>&2
		exit 1
	fi
fi

if [ $# -eq 0 ]; then
	echo "no sbt command given"
	exit 1
fi

SBT_OPTS="-Xms128M -Xmx1G"
SBT_CMD=$@

java -ea -server -Xmx512M						\
	-XX:+AggressiveOpts						\
	-XX:+OptimizeStringConcat					\
	-XX:+UseConcMarkSweepGC						\
	-XX:+CMSParallelRemarkEnabled					\
	-XX:+CMSClassUnloadingEnabled					\
	-jar target/${sbtjar} $@

