ARG VERSION="7.0-nightly"
ARG MARKER="a"

# Intermediate container here to build district computations
FROM golang:1.23.2 as appstarter_builder

WORKDIR /usr/src/app
COPY appstarter/ ./
RUN go build cmd/appstarter.go

FROM ghcr.io/opendcs/compproc:${VERSION} as compproc
ARG VERSION
ARG MARKER
#HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
ENV IMAGE_MARKER=${MARKER}
CMD [ "/appstarter", "decodes.tsdb.ComputationApp"]

FROM ghcr.io/opendcs/compdepends:${VERSION} as compdepends
ARG VERSION
ARG MARKER
##HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
ENV IMAGE_MARKER=${MARKER}
CMD [ "/appstarter", "decodes.tsdb.CpCompDependsUpdater"]

FROM ghcr.io/opendcs/routingscheduler:${VERSION} as routingscheduler
ARG VERSION
ARG MARKER
##HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
ENV IMAGE_MARKER=${MARKER}
CMD [ "/appstarter", "decodes.routing.RoutingScheduler"]
