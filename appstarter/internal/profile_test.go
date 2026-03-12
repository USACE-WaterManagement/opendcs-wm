package opendcs

import (
	"os"
	"testing"
)

func TestProfiles(t *testing.T) {

	env := EnvironmentVars{"XML", "jdbc:test", "noop", "org.opendcs.testing", "SPK",
		"CWMS", "fake", "compproc", "/tmp/installDir", "/tmp/userDir"}

	profiles := GetProfiles(env, ProfileTemplate)

	if len(profiles) != 1 {
		t.Fatalf("Not all profiles created. Only %d", len(profiles))
	}

	for _, profile := range profiles {
		if profile.Office != "SPK" {
			t.Fatal("App name not set correctly.")
		}

		_, err := os.ReadFile(profile.ProfileFile)
		if err != nil {
			t.Fatalf("Profile not created %s", err)
		}
		os.Remove(profile.ProfileFile)
	}

}
