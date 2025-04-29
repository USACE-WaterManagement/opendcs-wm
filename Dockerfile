ARG VERSION="main-nightly"
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

FROM ghcr.io/opendcs/compproc:${VERSION} AS apps
ARG VERSION
ARG MARKER
# Add add in the custom algos
COPY --from=algo_builder /home/gradle/project/distribution/build/install/district-algorithms/ /dcs_user_dir/dep/
#HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
ENV IMAGE_MARKER=${MARKER}
ENTRYPOINT ["/appstarter"]

FROM ghcr.io/opendcs/lrgs:${VERSION} AS lrgs
ARG VERSION
ARG MARKER
ENV IMAGE_MARKER=${MARKER}
COPY --chmod=0555 scripts/lrgs-cwbi.sh /
CMD ["/lrgs-cwbi.sh"]

FROM ghcr.io/opendcs/compproc:${VERSION} AS migration
ARG VERSION
ARG MARKER
USER root
RUN apk add --no-cache util-linux
ENV IMAGE_MARKER=${MARKER}
COPY --chmod=0555 scripts/migrate.sh /
USER opendcs:opendcs
WORKDIR /dcs_user_dir
CMD ["/migrate.sh"]

# TODO API - waiting on some verification of the API status