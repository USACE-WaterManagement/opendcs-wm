ARG VERSION="7.5-nightly"
ARG MARKER="a"

# Intermediate container here to build district computations
FROM golang:1.24.2 AS appstarter_builder
WORKDIR /usr/src/app
COPY appstarter/ ./
RUN go build cmd/appstarter.go

FROM gradle:8.13-jdk AS algo_builder
COPY algorithms /home/gradle/project
WORKDIR /home/gradle/project
RUN ./gradlew installDist --info

FROM ghcr.io/opendcs/compproc:${VERSION} AS compproc
ARG VERSION
ARG MARKER
# Add add in the custom algos
COPY --from=algo_builder /home/gradle/project/distribution/build/install/district-algorithms/ /dcs_user_dir/dep/
#HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
ENV IMAGE_MARKER=${MARKER}
CMD [ "/appstarter", "decodes.tsdb.ComputationApp"]

FROM ghcr.io/opendcs/compdepends:${VERSION} AS compdepends
ARG VERSION
ARG MARKER
##HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
ENV IMAGE_MARKER=${MARKER}
CMD [ "/appstarter", "decodes.tsdb.CpCompDependsUpdater"]

FROM ghcr.io/opendcs/routingscheduler:${VERSION} AS routingscheduler
ARG VERSION
ARG MARKER
##HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
ENV IMAGE_MARKER=${MARKER}
CMD [ "/appstarter", "decodes.routing.RoutingScheduler"]
