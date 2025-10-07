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

exec manageDatabase -I ${OPENDCS_IMPLEMENTATION} \
               -P /dcs_user_dir/user.properties \
               -username ${FLYWAY_USERNAME} \
               -password ${FLYWAY_PASSWORD} \
               -DCWMS_SCHEMA=CWMS_20 \
               -DCCP_SCHEMA=CCP \
               -DDEFAULT_OFFICE=LRL \
               -DDEFAULT_OFFICE_CODE=9 \
               -Dopendcs.flyway.migrate=true \
               -DTABLE_SPACE_SPEC= \
               -appUsername "${DCS_USERNAME}" \
               -appPassword "${DCS_PASSWORD}"
