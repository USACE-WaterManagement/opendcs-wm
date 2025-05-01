#!/bin/bash

export DATABASE_URL=$JDBC_URL
export CWMS_OFFICE=LRL
export DATABASE_TYPE=CWMS
export DATABASE_DRIVER="oracle.jdbc.driver.OracleDriver"
export DATATYPE_STANDARD="CWMS"
export KEYGENERATOR="decodes.sql.OracleSequenceKeyGenerator"

source /opt/opendcs/tsdb_config.sh

cat /dcs_user_dir/user.properties

script -c "manageDatabase -I ${OPENDCS_IMPLEMENTATION} -P /dcs_user_dir/user.properties" <<EOF
${FLYWAY_USERNAME}
${FLYWAY_PASSWORD}
CWMS_20
CCP
1
HQ

${DATABASE_USERNAME}
${DATABASE_PASSWORD}
${DATABASE_PASSWORD}
EOF