#!/bin/bash


export DB_URL=$JDBC_URL
export DATABASE_TYPE=CWMS-Oracle
export DATABASE_IMPLEMENTATION=CWMS-Oracle
export DB_DRIVER_CLASS=oracle.jdbc.driver.OracleDriver
export DB_MAX_CONNECTIONS=30
export DB_MAX_IDLE=10
export DB_MIN_IDLE=5
export DB_USERNAME=$DCS_USERNAME
export DB_PASSWORD=$DCS_PASSWORD
export DB_VALIDATION_QUERY="select 1 from dual"

exec /usr/local/tomcat/bin/catalina.sh run