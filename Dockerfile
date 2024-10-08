# Intermediate container here to build district computations
FROM golang:1.23.2 as appstarter_builder

WORKDIR /usr/src/app
COPY appstarter/ ./
RUN go build cmd/appstarter.go

FROM ghcr.io/opendcs/compproc:7.0.13-RC08 as compproc
#HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=appstarter_builder /usr/src/app/appstarter /
CMD [ "/appstarter", "decodes.tsdb.ComputationApp"]

FROM ghcr.io/opendcs/compdepends:7.0.13-RC08 as compdepends
##HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --chmod=0555 --from=builder COPY --from=builder /apps/cmd/appstarter /
CMD [ "/appstarter", "decodes.tsdb.CpCompDependsUpdater"]
