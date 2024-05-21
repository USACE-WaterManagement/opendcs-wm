# Intermediate container here to build district computations
FROM alpine:3.19.1 as builder

RUN apk update && apk upgrade 
RUN apk add cmake openssl-dev openssl-libs-static libpq-dev postgresql-dev gcc g++ make git boost-dev boost-static zlib-static libpq postgresql-common binutils-dev build-base
ADD . /src/
RUN mkdir -p /src/build
WORKDIR /src/build
RUN cmake -DCMAKE_EXE_LINKER_FLAGS="-static -static-libgcc -static-libstdc++" .. \
    && make && strip appstarter/src/appstarter
RUN mkdir /driver_app \
    && cp appstarter/src/appstarter /driver_app/appstarter \
    && cp /src/decodes.properties.template /driver_app/decodes.properties.template

FROM ghcr.io/opendcs/opendcs/compproc:7.0.12 as compproc

COPY --from=builder /driver_app /
CMD [ "/appstarter", "/decodes.properties.template"]

FROM ghcr.io/opendcs/opendcs/compdepends:7.0.12 as compdepends

COPY --from=builder /driver_app /
CMD [ "/appstarter", "/decodes.properties.template"]
