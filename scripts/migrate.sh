#!/bin/bash

export DATABASE_URL=$JDBC_URL
export CWMS_OFFICE=LRL
export DATABASE_TYPE=CWMS
export DATABASE_DRIVER="oracle.jdbc.driver.OracleDriver"
export DATATYPE_STANDARD="CWMS"
export KEYGENERATOR="decodes.sql.OracleSequenceKeyGenerator"

source /opt/opendcs/tsdb_config.sh
echo "***** GENERATED PROPERTIES FILE *****"
cat /dcs_user_dir/user.properties
echo "***** END GENERATED PROPERTIES FILE *****"


# Build classpath
CP=$DCSTOOL_HOME/bin/opendcs.jar

# If a user-specific 'dep' (dependencies) directory exists, then
# add all the jars therein to the classpath.
if [ -d "$DCSTOOL_USERDIR/dep" ]; then
  CP=$CP:$DCSTOOL_USERDIR/dep/*
fi
CP=$CP:$DCSTOOL_HOME/dep/*

exec java -Xms120m -cp $CP \
    -Dlogback.configurationFile=$DCSTOOL_HOME/logback.xml \
    -DAPP_NAME=migration \
    -DLOG_LEVEL=${LOG_LEVEL:-INFO} \
    -DDCSTOOL_HOME=$DCSTOOL_HOME -DDECODES_INSTALL_DIR=$DCSTOOL_HOME -DDCSTOOL_USERDIR=$DCSTOOL_USERDIR \
    org.opendcs.database.ManageDatabaseApp -I OpenDCS-Postgres \
    -P /dcs_user_dir/user.properties \
    -username ${FLYWAY_USERNAME} \
    -password ${FLYWAY_PASSWORD} \
    -DCWMS_SCHEMA=CWMS_20 \
    -DCCP_SCHEMA=CCP \
    -DDEFAULT_OFFICE=LRL \
    -DDEFAULT_OFFICE_CODE=9 \
    -Dopendcs.flyway.baseline=false \
    -DTABLE_SPACE_SPEC= \
    -appUsername "${DCS_USERNAME}" \
    -appPassword "${DCS_PASSWORD}"
