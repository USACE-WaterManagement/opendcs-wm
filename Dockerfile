# Intermediate container here to build district computations
FROM golang:1.23.2 as appstarter_builder

WORKDIR /usr/src/app
COPY appstarter/go.mod ./
RUN go mod download && go mod verify
RUN ls && go build -o appstarter

FROM ghcr.io/opendcs/compproc:7.0.13-RC08 as compproc
#HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
COPY --from=appstarter_builder /apps/cmd/appstarter /
CMD [ "/appstarter"]

#FROM ghcr.io/opendcs/opendcs/compdepends:7.0.13-RC08 as compdepends
##HEALTHCHECK --interval=2m --timeout=30s --start-period=60s --retries=3 CMD [ "/appstarter", "--check" ]
#COPY --from=builder COPY --from=builder /apps/cmd/appstarter /
#CMD [ "/appstarter"]
