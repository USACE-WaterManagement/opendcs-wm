package opendcs

import (
	"os"
	"reflect"
	"testing"
)

func TestEnvironment(t *testing.T) {
	installDir, _ := os.CreateTemp("/tmp", "testing")
	userDir, _ := os.CreateTemp("/tmp", "testing")
	t.Setenv(USERDIR, userDir.Name())
	t.Setenv(DCSTOOL_HOME, installDir.Name())
	t.Setenv(DATABASE_TYPE, "XML")
	t.Setenv(DATABASE_URL, "jdbc:test")
	t.Setenv(DATABASE_DRIVER, "org.opendcs.testing")
	t.Setenv(DATATYPE_STANDARD, "CWMS")
	t.Setenv(DATABASE_AUTH, "noop")
	t.Setenv(OFFICE, "SPK,LRL,SPA")
	t.Setenv(KEY_GENERATOR, "fake")
	t.Setenv(APPLICATION_NAME, "testApp")

	t.Run("environment is correct", func(t *testing.T) {
		env := CurrentEnvironment()
		if env.ApplicationName != "testApp" {
			t.Fatal("Wrong Application name set.")
		}

		if env.DataTypeStandard != "CWMS" {
			t.Fatal("Wrong DataType standard set.")
		}

		if env.DatabaseAuth != "noop" {
			t.Fatal("Wrong auth set.")
		}

		if env.DatabaseDriver != "org.opendcs.testing" {
			t.Fatal("Wrong driver set.")
		}

		if env.DatabaseType != "XML" {
			t.Fatal("Wrong database type set.")
		}

		if env.DatabaseUrl != "jdbc:test" {
			t.Fatal("Wrong database user set.")
		}

		if env.InstallDir != installDir.Name() {
			t.Fatal("Wrong install directory set.")
		}

		if env.UserDir != userDir.Name() {
			t.Fatal("Wrong user directory set.")
		}

		if env.KeyGenerator != "fake" {
			t.Fatal("Wrong key generator set.")
		}

		if len(env.Offices) != 3 {
			t.Fatal("Wrong number of offices set")
		}

		if !reflect.DeepEqual(env.Offices, []string{"SPK", "LRL", "SPA"}) {
			t.Fatal("Wrong offices set.")
		}
	})
}
