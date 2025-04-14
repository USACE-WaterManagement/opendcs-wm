package opendcs

import (
	"log"
	"os"
	"strings"
)

////go:embed decodes.properties.template
//var template string

const USERDIR = "DCSTOOL_USERDIR"
const DATABASE_TYPE = "DATABASE_TYPE"
const DATABASE_URL = "DATABASE_URL"
const DATABASE_AUTH = "DB_AUTH"
const DATABASE_DRIVER = "DATABASE_DRIVER"
const OFFICE = "CWMS_OFFICE"
const DATATYPE_STANDARD = "DATATYPE_STANDARD"
const KEY_GENERATOR = "KEYGENERATOR"
const APPLICATION_NAME = "APPLICATION_NAME"
const DCSTOOL_HOME = "DCSTOOL_HOME"

type EnvironmentVars struct {
	DatabaseType     string
	DatabaseUrl      string
	DatabaseAuth     string
	DatabaseDriver   string
	Office           string
	DataTypeStandard string
	KeyGenerator     string
	ApplicationNames  []string

	InstallDir string
	UserDir    string
}

func CurrentEnvironment() EnvironmentVars {
	var databaseType = os.Getenv(DATABASE_TYPE)
	var databaseUrl = os.Getenv(DATABASE_URL)
	var databaseAuth = os.Getenv(DATABASE_AUTH)
	if databaseAuth == "" {
		databaseAuth = "env-auth-source:username=DATABASE_USERNAME,password=DATABASE_PASSWORD"
	}
	var databaseDriver = os.Getenv(DATABASE_DRIVER)
	var datatypeStandard = os.Getenv(DATATYPE_STANDARD)
	var keyGenerator = os.Getenv(KEY_GENERATOR)
	var applicationNames = strings.Split(os.Getenv(APPLICATION_NAME), ",")
	var office = os.Getenv(OFFICE)

	var installDirStr = os.Getenv(DCSTOOL_HOME)
	var userDirStr = os.Getenv(USERDIR)
	if _, err := os.Open(installDirStr); err != nil {
		log.Fatal("Install Directory doesn't exist in this environment.")
	}
	if _, err := os.Open(userDirStr); err != nil {
		log.Fatal("User Directory Doesn't exist in this environment.")
	}

	ret := EnvironmentVars{databaseType, databaseUrl, databaseAuth, databaseDriver,
		office, datatypeStandard, keyGenerator, applicationNames, installDirStr, userDirStr}
	return ret
}
